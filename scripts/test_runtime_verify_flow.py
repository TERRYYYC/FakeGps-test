#!/usr/bin/env python3
"""Parse and validate release-visible runtime verification evidence.

This harness is read-only. It never installs an APK, edits Vector state, clears logcat, or mutates
the user's profile. UI/configuration actions required by the device matrix remain explicit operator
steps after review.
"""

import argparse
import json
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Optional, Sequence, Tuple


TOKEN = r"[A-Za-z0-9._:-]+"
FINGERPRINT = r"sha256:[0-9a-f]{16}"
PROBE_RE = re.compile(
    rf"FakeGPS-Probe(?:\(\s*[0-9]+\))?:\s+event=(requested|delivered|failed|ignored) "
    rf"requestId=({TOKEN}) fp=({FINGERPRINT})"
    rf"(?: fields=([0-9]+)| reason=([A-Z_]+))?\s*$"
)
OWNER_RE = re.compile(
    rf"FakeGPS-Hook:\s+event=scheduler_owned process=({TOKEN}) intervalMs=([0-9]+)\s*$"
)
CHANGE_RE = re.compile(
    rf"FakeGPS-Hook:\s+event=interval_changed process=({TOKEN}) "
    rf"fromMs=([0-9]+) toMs=([0-9]+)\s*$"
)
BRIEF_PID_RE = re.compile(r"(?:^|\s)[VDIWEF]/[^\r\n(]+\(\s*([0-9]+)\):")


@dataclass(frozen=True)
class RuntimeEvent:
    event: str
    pid: Optional[int] = None
    request_id: Optional[str] = None
    fingerprint: Optional[str] = None
    fields: Optional[int] = None
    reason: Optional[str] = None
    process: Optional[str] = None
    interval_ms: Optional[int] = None
    from_ms: Optional[int] = None
    to_ms: Optional[int] = None


@dataclass(frozen=True)
class RuntimeVerifyVerdict:
    passed: bool
    errors: Tuple[str, ...]
    events: Tuple[RuntimeEvent, ...]


def _logcat_pid(line: str) -> Optional[int]:
    match = BRIEF_PID_RE.search(line)
    return int(match.group(1)) if match else None


def parse_line(line: str) -> Optional[RuntimeEvent]:
    pid = _logcat_pid(line)
    match = PROBE_RE.search(line.strip())
    if match:
        event, request_id, fingerprint, fields, reason = match.groups()
        if event == "delivered" and fields is None:
            return None
        if event in {"failed", "ignored"} and reason is None:
            return None
        if event == "requested" and (fields is not None or reason is not None):
            return None
        return RuntimeEvent(
            event=event,
            pid=pid,
            request_id=request_id,
            fingerprint=fingerprint,
            fields=int(fields) if fields is not None else None,
            reason=reason,
        )
    match = OWNER_RE.search(line.strip())
    if match:
        process, interval_ms = match.groups()
        value = int(interval_ms)
        if value <= 0:
            return None
        return RuntimeEvent(
            event="scheduler_owned", pid=pid, process=process, interval_ms=value
        )
    match = CHANGE_RE.search(line.strip())
    if match:
        process, from_ms, to_ms = match.groups()
        before, after = int(from_ms), int(to_ms)
        if before <= 0 or after <= 0 or before == after:
            return None
        return RuntimeEvent(
            event="interval_changed",
            pid=pid,
            process=process,
            from_ms=before,
            to_ms=after,
        )
    return None


def verify_trace(
    lines: Iterable[str],
    *,
    expected_intervals: Sequence[int] = (),
    expected_fingerprint: Optional[str] = None,
    expected_probe_failure: Optional[str] = None,
    require_timeout_retry: bool = False,
    probe_process_gone: Optional[bool] = None,
    require_probe: bool = False,
    require_scheduler: bool = False,
) -> RuntimeVerifyVerdict:
    events = tuple(event for line in lines if (event := parse_line(line)) is not None)
    errors = []
    requested = {}
    for index, event in enumerate(events):
        if event.event == "requested":
            requested.setdefault((event.request_id, event.fingerprint), []).append(index)
    delivered = [event for event in events if event.event == "delivered"]
    for terminal_index, event in enumerate(events):
        if event.event != "delivered":
            continue
        request_indexes = requested.get((event.request_id, event.fingerprint), ())
        if not any(index < terminal_index for index in request_indexes):
            errors.append("unmatched delivered")
    for terminal_index, event in enumerate(events):
        if event.event != "failed":
            continue
        request_indexes = requested.get((event.request_id, event.fingerprint), ())
        if not any(index < terminal_index for index in request_indexes):
            errors.append("unmatched failed")

    terminal_counts = {}
    for event in events:
        if event.event in {"delivered", "failed"}:
            key = (event.request_id, event.fingerprint)
            terminal_counts[key] = terminal_counts.get(key, 0) + 1
    if any(count > 1 for count in terminal_counts.values()):
        errors.append("multiple terminal events for request")

    for ignored_index, ignored in enumerate(events):
        if ignored.event != "ignored":
            continue
        if ignored.reason != "STALE_RESULT":
            errors.append("invalid ignored reason")
        key = (ignored.request_id, ignored.fingerprint)
        if not any(index < ignored_index for index in requested.get(key, ())):
            errors.append("unmatched ignored")
        prior_requests = [
            event
            for index, event in enumerate(events)
            if index < ignored_index and event.event == "requested"
        ]
        if prior_requests and (
            prior_requests[-1].request_id,
            prior_requests[-1].fingerprint,
        ) == key:
            errors.append("ignored active result")

    owners_by_process = {}
    for event in events:
        if event.event == "scheduler_owned":
            owners_by_process.setdefault(event.process, []).append(event.pid)
    for process, pids in owners_by_process.items():
        if None in pids:
            if len(pids) != 1:
                errors.append(f"duplicate scheduler owner for {process}")
            continue
        pid_counts = {pid: pids.count(pid) for pid in set(pids)}
        for pid, count in pid_counts.items():
            if count > 1:
                errors.append(f"duplicate scheduler owner for {process} pid={pid}")

    observed_intervals = {
        event.to_ms for event in events if event.event == "interval_changed"
    } | {
        event.interval_ms for event in events if event.event == "scheduler_owned"
    }
    for expected in expected_intervals:
        if expected not in observed_intervals:
            errors.append(f"missing interval {expected}")

    if require_probe and not delivered:
        errors.append("no correlated probe delivery")
    if require_scheduler and not owners_by_process:
        errors.append("no scheduler owner evidence")

    if expected_probe_failure is not None:
        if not any(
            event.event == "failed"
            and event.reason == expected_probe_failure
            and (expected_fingerprint is None or event.fingerprint == expected_fingerprint)
            for event in events
        ):
            errors.append(f"missing probe failure {expected_probe_failure}")
    elif expected_fingerprint is not None:
        if not any(
            event.event == "delivered" and event.fingerprint == expected_fingerprint
            for event in events
        ):
            errors.append(f"missing delivered fingerprint {expected_fingerprint}")

    if require_timeout_retry:
        timeouts = [
            (index, event)
            for index, event in enumerate(events)
            if event.event == "failed" and event.reason == "TIMEOUT"
        ]
        if not timeouts:
            errors.append("no timeout evidence")
        if probe_process_gone is not True:
            errors.append("probe process survived timeout")
        if timeouts:
            timeout_index, timeout = timeouts[-1]
            if any(
                index > timeout_index
                and event.event == "delivered"
                and event.request_id == timeout.request_id
                for index, event in enumerate(events)
            ):
                errors.append("timed-out request delivered")
            retries = [
                (index, event)
                for index, event in enumerate(events)
                if index > timeout_index
                and event.event == "requested"
                and event.request_id != timeout.request_id
            ]
            if not retries:
                errors.append("timeout retry reused or omitted requestId")
            else:
                retry_index, retry = retries[0]
                if not any(
                    index > retry_index
                    and event.event == "delivered"
                    and event.request_id == retry.request_id
                    and event.fingerprint == retry.fingerprint
                    for index, event in enumerate(events)
                ):
                    errors.append("fresh retry was not delivered")

    if not events:
        errors.append("no runtime evidence")
    return RuntimeVerifyVerdict(not errors, tuple(errors), events)


def _adb_lines() -> Sequence[str]:
    completed = subprocess.run(
        ["adb", "logcat", "-d", "-v", "brief"],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.splitlines()


def _probe_process_gone(package: str) -> bool:
    completed = subprocess.run(
        ["adb", "shell", "pidof", f"{package}:hook_verify"],
        check=False,
        capture_output=True,
        text=True,
    )
    return not completed.stdout.strip()


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--log-file", type=Path)
    source.add_argument("--from-adb", action="store_true")
    parser.add_argument("--expected-interval-ms", action="append", type=int, default=[])
    parser.add_argument("--expected-fingerprint")
    parser.add_argument("--expected-probe-failure")
    parser.add_argument("--require-timeout-retry", action="store_true")
    parser.add_argument("--require-probe", action="store_true")
    parser.add_argument("--require-scheduler", action="store_true")
    parser.add_argument("--package", default="name.caiyao.fakegps")
    args = parser.parse_args(argv)

    lines = _adb_lines() if args.from_adb else args.log_file.read_text().splitlines()
    gone = _probe_process_gone(args.package) if args.require_timeout_retry else None
    verdict = verify_trace(
        lines,
        expected_intervals=args.expected_interval_ms,
        expected_fingerprint=args.expected_fingerprint,
        expected_probe_failure=args.expected_probe_failure,
        require_timeout_retry=args.require_timeout_retry,
        probe_process_gone=gone,
        require_probe=args.require_probe,
        require_scheduler=args.require_scheduler,
    )
    print(json.dumps({
        "passed": verdict.passed,
        "errors": verdict.errors,
        "events": [asdict(event) for event in verdict.events],
    }, sort_keys=True))
    return 0 if verdict.passed else 1


if __name__ == "__main__":
    sys.exit(main())
