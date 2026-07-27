import json
import re
import unittest
from pathlib import Path

from scripts import cellular_acceptance_matrix as matrix


class CellularAcceptanceMatrixTest(unittest.TestCase):

    def test_two_scenarios_cover_wcdma_power_aliases_without_ambiguity(self):
        self.assertEqual(
            ("full-rscp", "full-rssi", "fluctuation-enabled"),
            matrix.scenario_names(),
        )

        rscp = matrix.get_scenario("full-rscp")
        rssi = matrix.get_scenario("full-rssi")

        self.assertEqual(-88, rscp.fields["wcdma_rscp"])
        self.assertNotIn("wcdma_rssi", rscp.fields)
        self.assertEqual(-85, rssi.fields["wcdma_rssi"])
        self.assertNotIn("wcdma_rscp", rssi.fields)
        self.assertEqual(
            set(rscp.fields).difference({"wcdma_rscp"}),
            set(rssi.fields).difference({"wcdma_rssi"}),
        )

    def test_payload_is_canonical_schema_v2_and_preserves_nr_long_width(self):
        payload = matrix.payload_for("full-rscp")

        self.assertEqual(
            ["fields", "mode", "schemaVersion"],
            sorted(payload),
        )
        self.assertEqual(2, payload["schemaVersion"])
        self.assertEqual("always_on", payload["mode"])
        self.assertEqual(68_719_400_000, payload["fields"]["nci"])
        self.assertIs(type(payload["fields"]["nci"]), int)
        self.assertEqual(
            payload,
            json.loads(
                matrix.emit_json("full-rscp", matrix.OUTPUT_PAYLOAD),
            ),
        )

    def test_expected_paths_cover_every_delivery_surface_and_exact_type(self):
        expected = matrix.expected_for("full-rscp")

        for delivery in ("sync", "request"):
            for radio in ("lte", "gsm", "wcdma", "nr"):
                prefix = "cellInfo.{}.{}.".format(delivery, radio)
                self.assertTrue(
                    any(path.startswith(prefix) for path in expected),
                    prefix,
                )
        for radio in ("lte", "gsm", "wcdma", "nr"):
            prefix = "callback.cellInfo.{}.".format(radio)
            self.assertTrue(
                any(path.startswith(prefix) for path in expected),
                prefix,
            )
        for prefix in (
            "cellInfo.sync",
            "cellInfo.request",
            "callback.cellInfo",
        ):
            for radio in ("gsm", "lte", "wcdma"):
                path = "{}.neighbors.{}.registered".format(prefix, radio)
                self.assertIn(path, expected)
                self.assertIs(expected[path], False)

        self.assertIs(expected["telephony.isNetworkRoaming"], True)
        self.assertIs(expected["cellInfo.requestCompleted"], True)
        self.assertIs(expected["callback.completed"], True)
        self.assertEqual(
            "hook_replay_after_permission_denied",
            expected["callback.physicalChannelDelivery"],
        )
        self.assertEqual(
            68_719_400_000,
            expected["cellInfo.sync.nr.nci"],
        )
        self.assertEqual(
            40_000,
            expected["callback.physicalChannel.cellBandwidthUplinkKhz"],
        )
        self.assertEqual(
            100_000,
            expected["callback.physicalChannel.cellBandwidthDownlinkKhz"],
        )

    def test_every_configured_field_has_an_expected_public_observation(self):
        for name in matrix.scenario_names():
            scenario = matrix.get_scenario(name)
            covered = matrix.covered_profile_fields(name)
            self.assertEqual(set(scenario.fields), covered, name)

        self.assertNotIn(
            "signal_fluctuation_enabled",
            matrix.get_scenario("full-rscp").fields,
        )
        fluctuation = matrix.get_scenario("fluctuation-enabled")
        self.assertEqual(1, fluctuation.fields["signal_fluctuation_enabled"])
        self.assertEqual(6, fluctuation.fields["signal_fluctuation_range_db"])
        self.assertEqual(
            {
                "$matcher": "complete_int_set",
                "allowed": [-104, -103, -102, -101, -100, -99, -98],
                "count": 256,
            },
            matrix.expected_for("fluctuation-enabled")[
                "cellInfo.sync.lte.rsrpSamples"
            ],
        )

    def test_api_33_gate_belongs_only_to_fixed_matrix_preflight(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        device_preflight = self._shell_function(script, "preflight_device")
        matrix_preflight = self._shell_function(script, "preflight_matrix")

        self.assertNotIn("API 33", device_preflight)
        self.assertIn('[ "$DEVICE_API" -ge 33 ]', matrix_preflight)
        self.assertIn(
            "Android API 33+ required for --cellular-matrix",
            matrix_preflight,
        )

    def test_cli_emits_deterministic_json_and_rejects_unknown_scenarios(self):
        first = matrix.emit_json("full-rssi", matrix.OUTPUT_EXPECTED)
        second = matrix.emit_json("full-rssi", matrix.OUTPUT_EXPECTED)
        self.assertEqual(first, second)
        self.assertEqual(
            -85,
            json.loads(first)["cellInfo.sync.wcdma.dbm"],
        )

        lines = []
        self.assertEqual(
            2,
            matrix.main(
                ["missing", "--output", matrix.OUTPUT_PAYLOAD],
                emit=lines.append,
            ),
        )
        self.assertEqual(1, len(lines))
        self.assertTrue(lines[0].startswith("MATRIX_ERROR "))

    @staticmethod
    def _shell_function(script, name):
        match = re.search(
            r"^{}\(\) \{{\n(.*?)^\}}\n".format(re.escape(name)),
            script,
            flags=re.MULTILINE | re.DOTALL,
        )
        if match is None:
            raise AssertionError("missing shell function: {}".format(name))
        return match.group(1)


if __name__ == "__main__":
    unittest.main()
