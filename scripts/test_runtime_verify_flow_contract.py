import unittest

from scripts import test_runtime_verify_flow as runtime_flow


FP1 = "sha256:1111111111111111"
FP2 = "sha256:2222222222222222"


class RuntimeVerifyFlowContractTest(unittest.TestCase):
    def test_parser_reads_probe_and_scheduler_evidence_from_logcat(self):
        requested = runtime_flow.parse_line(
            f"08-01 20:00:00.000 I/FakeGPS-Probe: event=requested requestId=r1 fp={FP1}"
        )
        delivered = runtime_flow.parse_line(
            f"I/FakeGPS-Probe( 1234): event=delivered requestId=r1 fp={FP1} fields=7"
        )
        changed = runtime_flow.parse_line(
            "08-01 20:00:02.000 I/LSPosed: FakeGPS-Hook: event=interval_changed "
            "process=com.example fromMs=30000 toMs=5000"
        )

        self.assertEqual(("requested", "r1", FP1), (requested.event, requested.request_id, requested.fingerprint))
        self.assertEqual(7, delivered.fields)
        self.assertEqual((30000, 5000), (changed.from_ms, changed.to_ms))

    def test_parser_rejects_malformed_ids_fingerprints_and_intervals(self):
        malformed = (
            "FakeGPS-Probe: event=requested requestId=has spaces fp=sha256:1111111111111111",
            "FakeGPS-Probe: event=requested requestId=r1 fp=not-a-fingerprint",
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=-1",
        )
        self.assertTrue(all(runtime_flow.parse_line(line) is None for line in malformed))

    def test_stale_delivery_cannot_pass_the_trace(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=r2 fp={FP2} fields=1",
        ]
        verdict = runtime_flow.verify_trace(lines)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched delivered", verdict.errors)

    def test_terminal_event_before_request_cannot_pass_by_key_coincidence(self):
        lines = [
            f"FakeGPS-Probe: event=delivered requestId=r1 fp={FP1} fields=1",
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
        ]
        verdict = runtime_flow.verify_trace(lines)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched delivered", verdict.errors)

    def test_ignored_stale_callback_is_evidence_but_not_a_terminal_delivery(self):
        valid = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP2}",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=ignored requestId=old fp={FP2} reason=STALE_RESULT",
        ]
        self.assertTrue(runtime_flow.verify_trace(valid).passed)

        active = [
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=ignored requestId=current fp={FP1} reason=STALE_RESULT",
        ]
        verdict = runtime_flow.verify_trace(active)
        self.assertFalse(verdict.passed)
        self.assertIn("ignored active result", verdict.errors)

        unknown = [
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=ignored requestId=old fp={FP2} reason=STALE_RESULT",
        ]
        verdict = runtime_flow.verify_trace(unknown)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched ignored", verdict.errors)

    def test_unmatched_failure_cannot_masquerade_as_the_active_request(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=old fp={FP2} reason=NOT_SCOPED",
        ]
        verdict = runtime_flow.verify_trace(lines)
        self.assertFalse(verdict.passed)
        self.assertIn("unmatched failed", verdict.errors)

    def test_timeout_retry_requires_process_exit_new_id_and_fresh_delivery(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=r1 fp={FP1} reason=TIMEOUT",
            f"FakeGPS-Probe: event=requested requestId=r2 fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=r2 fp={FP1} fields=1",
        ]
        self.assertFalse(
            runtime_flow.verify_trace(
                lines,
                require_timeout_retry=True,
                probe_process_gone=False,
            ).passed
        )
        self.assertTrue(
            runtime_flow.verify_trace(
                lines,
                require_timeout_retry=True,
                probe_process_gone=True,
            ).passed
        )

        stale_green = lines[:-1] + [
            f"FakeGPS-Probe: event=delivered requestId=r1 fp={FP1} fields=1",
            lines[-1],
        ]
        verdict = runtime_flow.verify_trace(
            stale_green,
            require_timeout_retry=True,
            probe_process_gone=True,
        )
        self.assertFalse(verdict.passed)
        self.assertIn("timed-out request delivered", verdict.errors)
        self.assertIn("multiple terminal events for request", verdict.errors)

    def test_interval_matrix_and_single_scheduler_owner_are_strict(self):
        lines = [
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=30000",
            "FakeGPS-Hook: event=interval_changed process=com.example fromMs=30000 toMs=5000",
            "FakeGPS-Hook: event=interval_changed process=com.example fromMs=5000 toMs=60000",
        ]
        self.assertTrue(runtime_flow.verify_trace(lines, expected_intervals=(5000, 60000)).passed)
        duplicate = lines + [
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=60000"
        ]
        verdict = runtime_flow.verify_trace(duplicate, expected_intervals=(5000, 60000))
        self.assertFalse(verdict.passed)
        self.assertIn("duplicate scheduler owner for com.example", verdict.errors)

    def test_scheduler_owner_is_unique_per_android_pid_not_process_name(self):
        restarted = [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=30000",
            "I/LSPosed-Bridge( 101): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=30000",
        ]
        self.assertTrue(runtime_flow.verify_trace(restarted).passed)

        duplicate_in_one_process = restarted[:1] * 2
        verdict = runtime_flow.verify_trace(duplicate_in_one_process)
        self.assertFalse(verdict.passed)
        self.assertIn("duplicate scheduler owner for com.example pid=100", verdict.errors)

    def test_scheduler_owner_with_mixed_pid_provenance_fails_closed(self):
        mixed = [
            "I/LSPosed-Bridge( 100): FakeGPS-Hook: event=scheduler_owned "
            "process=com.example intervalMs=30000",
            "FakeGPS-Hook: event=scheduler_owned process=com.example intervalMs=30000",
        ]

        verdict = runtime_flow.verify_trace(mixed)

        self.assertFalse(verdict.passed)
        self.assertIn("duplicate scheduler owner for com.example", verdict.errors)

    def test_expected_fingerprint_and_not_scoped_failure_are_explicit_scenarios(self):
        not_scoped = [
            f"FakeGPS-Probe: event=requested requestId=r1 fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=r1 fp={FP1} reason=NOT_SCOPED",
        ]
        self.assertTrue(
            runtime_flow.verify_trace(
                not_scoped,
                expected_fingerprint=FP1,
                expected_probe_failure="NOT_SCOPED",
            ).passed
        )
        self.assertFalse(
            runtime_flow.verify_trace(not_scoped, expected_fingerprint=FP2).passed
        )

    def test_latest_probe_failure_cannot_be_masked_by_historical_delivery(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=old fp={FP1} fields=1",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=current fp={FP1} reason=NOT_SCOPED",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_fingerprint=FP1,
            require_probe=True,
        )

        self.assertFalse(verdict.passed)
        self.assertIn("latest probe was not delivered", verdict.errors)

    def test_expected_failure_must_belong_to_latest_probe(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP1}",
            f"FakeGPS-Probe: event=failed requestId=old fp={FP1} reason=NOT_SCOPED",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=current fp={FP1} fields=1",
        ]

        verdict = runtime_flow.verify_trace(
            lines,
            expected_fingerprint=FP1,
            expected_probe_failure="NOT_SCOPED",
        )

        self.assertFalse(verdict.passed)
        self.assertIn("missing probe failure NOT_SCOPED", verdict.errors)

    def test_expected_fingerprint_must_belong_to_latest_probe(self):
        lines = [
            f"FakeGPS-Probe: event=requested requestId=old fp={FP1}",
            f"FakeGPS-Probe: event=delivered requestId=old fp={FP1} fields=1",
            f"FakeGPS-Probe: event=requested requestId=current fp={FP2}",
            f"FakeGPS-Probe: event=delivered requestId=current fp={FP2} fields=1",
        ]

        verdict = runtime_flow.verify_trace(lines, expected_fingerprint=FP1)

        self.assertFalse(verdict.passed)
        self.assertIn(f"missing delivered fingerprint {FP1}", verdict.errors)


if __name__ == "__main__":
    unittest.main()
