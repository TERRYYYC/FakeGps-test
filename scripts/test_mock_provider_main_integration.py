#!/usr/bin/env python3
"""Structural contracts for main-app System Mock integration and truthful cleanup evidence."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class MockProviderMainIntegrationContractTest(unittest.TestCase):
    def test_lab_build_type_is_retired_in_favor_of_main_app_service(self) -> None:
        gradle = (ROOT / "app/build.gradle").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()

        self.assertNotIn("mockProvider {", gradle)
        self.assertFalse(
            any(path.is_file() for path in (ROOT / "app/src/mockProvider").rglob("*"))
        )
        self.assertIn('android:name=".mockprovider.MockProviderService"', manifest)
        self.assertIn('android:exported="false"', manifest)
        self.assertIn('android:foregroundServiceType="location"', manifest)

    def test_service_resolves_the_published_effective_profile_not_intent_coordinates(self) -> None:
        service = (ROOT / "app/src/main/java/name/caiyao/fakegps/mockprovider/MockProviderService.kt").read_text()
        contract = (ROOT / "app/src/main/java/name/caiyao/fakegps/mockprovider/MockProviderServiceContract.kt").read_text()

        self.assertIn("ConfigPrefsSync.readPublished", service)
        self.assertIn("PublishedConfig.parse", service)
        self.assertNotIn("EXTRA_LATITUDE", contract)
        self.assertNotIn("EXTRA_LONGITUDE", contract)
        self.assertNotIn("getDoubleExtra", service)

    def test_location_delivery_mode_only_bypasses_hook_location(self) -> None:
        writer = (ROOT / "app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt").read_text()
        hook = (ROOT / "app/src/main/java/name/caiyao/fakegps/hook/MainHook.java").read_text()
        policy = (ROOT / "app/src/main/java/name/caiyao/fakegps/hook/LocationDeliveryPolicy.java").read_text()

        self.assertIn('"locationDeliveryMode"', writer)
        self.assertIn("LocationDeliveryPolicy.apply", hook)
        for location_field in ("latitude", "longitude", "altitude", "speed", "bearing", "accuracy"):
            self.assertIn(f"snapshot.{location_field} = null", policy)
        self.assertNotIn("snapshot.tac = null", policy)
        self.assertNotIn("snapshot.wifiSsid = null", policy)

    def test_settings_exposes_one_system_mock_switch_and_developer_guidance(self) -> None:
        screen = (ROOT / "app/src/main/java/name/caiyao/fakegps/ui/screen/settings/SettingsScreen.kt").read_text()

        self.assertIn('Text("系统 Mock 位置")', screen)
        self.assertIn("Switch(", screen)
        self.assertIn("ACTION_APPLICATION_DEVELOPMENT_SETTINGS", screen)
        self.assertIn("生效中档案", screen)

    def test_kyiv_is_the_map_and_acceptance_coordinate(self) -> None:
        map_screen = (ROOT / "app/src/main/java/name/caiyao/fakegps/ui/screen/map/MapScreen.kt").read_text()
        harness = (ROOT / "scripts/mock_provider_acceptance.sh").read_text()

        self.assertIn("50.4501", map_screen)
        self.assertIn("30.5234", map_screen)
        self.assertIn("50.4501", harness)
        self.assertIn("30.5234", harness)

    def test_acceptance_asserts_actual_provider_identity_before_restoring_appop(self) -> None:
        harness = (ROOT / "scripts/mock_provider_acceptance.sh").read_text()

        self.assertIn("trap restore EXIT", harness)
        self.assertIn("name.caiyao.fakegps.bench", harness)
        self.assertNotIn('LAB_PACKAGE="name.caiyao.fakegps.mockprovider"', harness)
        self.assertNotIn("app-mockProvider.apk", harness)
        self.assertIn("assert_provider_is_mock", harness)
        self.assertIn("assert_provider_is_real", harness)
        self.assertIn("GnssService", harness)
        self.assertIn("gps provider", harness)
        self.assertIn('appops set "$BENCH_PACKAGE" android:mock_location allow', harness)
        self.assertIn('appops set "$REFERENCE_PACKAGE" android:mock_location allow', harness)


if __name__ == "__main__":
    unittest.main()
