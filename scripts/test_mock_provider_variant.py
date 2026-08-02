#!/usr/bin/env python3
"""Structural contract for the co-installable Mock Provider build variant."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class MockProviderVariantContractTest(unittest.TestCase):
    def test_build_type_has_an_independent_application_identity(self) -> None:
        gradle = (ROOT / "app/build.gradle").read_text()

        self.assertIn("mockProvider {", gradle)
        self.assertIn('applicationIdSuffix ".mockprovider"', gradle)
        self.assertIn('versionNameSuffix "-mock-provider"', gradle)

    def test_manifest_owned_names_follow_the_variant_application_id(self) -> None:
        main_manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        debug_manifest = (ROOT / "app/src/debug/AndroidManifest.xml").read_text()

        self.assertIn(
            'android:authorities="${applicationId}.data.AppInfoProvider"',
            main_manifest,
        )
        self.assertNotIn(
            'android:authorities="name.caiyao.fakegps.data.AppInfoProvider"',
            main_manifest,
        )
        self.assertIn(
            'android:name="${applicationId}.permission.RUN_HOOK_ACCEPTANCE"',
            debug_manifest,
        )
        self.assertIn(
            'android:permission="${applicationId}.permission.RUN_HOOK_ACCEPTANCE"',
            debug_manifest,
        )

    def test_lab_manifest_is_a_dedicated_non_xposed_launcher(self) -> None:
        overlay = (ROOT / "app/src/mockProvider/AndroidManifest.xml").read_text()
        strings = (ROOT / "app/src/mockProvider/res/values/strings.xml").read_text()

        self.assertIn('android:name="xposedmodule"', overlay)
        self.assertIn('tools:node="remove"', overlay)
        self.assertIn('android:name=".ui.ComposeActivity"', overlay)
        self.assertIn('android:name=".mockprovider.MockProviderActivity"', overlay)
        self.assertIn("FakeGPS Mock Provider Lab", strings)

    def test_device_harness_restores_the_original_mock_app_on_every_exit(self) -> None:
        harness = (ROOT / "scripts/mock_provider_acceptance.sh").read_text()

        self.assertIn("trap restore EXIT", harness)
        self.assertIn("com.hopefactory2021.fakegpslocation", harness)
        self.assertIn("name.caiyao.fakegps.mockprovider", harness)
        self.assertIn('appops set "$LAB_PACKAGE" android:mock_location deny', harness)
        self.assertIn('appops set "$REFERENCE_PACKAGE" android:mock_location allow', harness)
        self.assertNotIn("uninstall name.caiyao.fakegps", harness)

    def test_device_harness_starts_and_stops_through_the_foreground_activity(self) -> None:
        harness = (ROOT / "scripts/mock_provider_acceptance.sh").read_text()

        self.assertIn("MockProviderActivity", harness)
        self.assertIn('tap_text "Start"', harness)
        self.assertIn('tap_text "Stop"', harness)
        self.assertNotIn(
            'run-as "$LAB_PACKAGE" am start-foreground-service',
            harness,
        )


if __name__ == "__main__":
    unittest.main()
