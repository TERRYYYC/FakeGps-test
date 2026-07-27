import argparse
import json
import sys
from dataclasses import dataclass
from typing import Any, Dict, List

_MISSING = object()


@dataclass(frozen=True)
class FieldVerdict:
    path: str
    expected: Any
    observed: Any
    status: str
    reason: str


@dataclass(frozen=True)
class Verdict:
    fields: List[FieldVerdict]
    summary: Dict[str, Any]
    passed: bool


def evaluate(expected, report, session_id, restored):
    fields = []
    for path in sorted(expected):
        expected_value = expected[path]
        observed = _read_path(report, path)
        if observed is _MISSING:
            fields.append(
                FieldVerdict(path, expected_value, _MISSING, "FAILED", "missing")
            )
        elif type(observed) is not type(expected_value):
            fields.append(
                FieldVerdict(
                    path,
                    expected_value,
                    observed,
                    "FAILED",
                    "different_type",
                )
            )
        elif observed != expected_value:
            fields.append(
                FieldVerdict(path, expected_value, observed, "FAILED", "different")
            )
        else:
            fields.append(
                FieldVerdict(path, expected_value, observed, "VERIFIED", "exact")
            )

    if report.get("sessionId") != session_id:
        fields.append(
            FieldVerdict(
                "$sessionId",
                session_id,
                report.get("sessionId", _MISSING),
                "FAILED",
                "stale_session",
            )
        )

    errors = report.get("errors", _MISSING)
    if errors is _MISSING or not isinstance(errors, list) or errors:
        fields.append(
            FieldVerdict("$errors", [], errors, "FAILED", "probe_errors")
        )

    if not restored:
        fields.append(
            FieldVerdict("$restore", True, False, "FAILED", "restore_missing")
        )

    verified = sum(1 for item in fields if item.status == "VERIFIED")
    failed = sum(1 for item in fields if item.status == "FAILED")
    summary = {
        "configured": len(expected),
        "verified": verified,
        "failed": failed,
        "restored": bool(restored),
    }
    return Verdict(
        fields=fields,
        summary=summary,
        passed=failed == 0,
    )


def main(argv=None, emit=print):
    parser = argparse.ArgumentParser(
        description="Compare a FakeGps hook acceptance report with exact path expectations."
    )
    parser.add_argument("--expected-json", required=True)
    parser.add_argument("--report-file", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--restored", action="store_true")
    try:
        args = parser.parse_args(argv)
        expected = json.loads(args.expected_json)
        if not isinstance(expected, dict):
            raise ValueError("expected JSON must be an object")
        with open(args.report_file, encoding="utf-8") as report_file:
            report = json.load(report_file)
        if not isinstance(report, dict):
            raise ValueError("report JSON must be an object")
        verdict = evaluate(
            expected=expected,
            report=report,
            session_id=args.session_id,
            restored=args.restored,
        )
    except (OSError, ValueError, json.JSONDecodeError) as failure:
        emit("HARNESS_ERROR {}".format(failure))
        return 2

    for item in verdict.fields:
        line = "{} {} expected={} observed={}".format(
            item.status,
            item.path,
            _format_value(item.expected),
            _format_value(item.observed),
        )
        if item.status == "FAILED":
            line += " reason={}".format(item.reason)
        emit(line)
    emit(json.dumps(verdict.summary, separators=(",", ":")))
    return 0 if verdict.passed else 1


def _read_path(root, path):
    current = root
    for segment in path.split("."):
        if not isinstance(current, dict) or segment not in current:
            return _MISSING
        current = current[segment]
    return current


def _format_value(value):
    if value is _MISSING:
        return "<missing>"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    sys.exit(main())
