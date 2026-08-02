package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDeliveryUiContractTest {

    @Test
    fun `hook mode explains that only location delivery changes`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Idle,
            published(),
        )

        assertFalse(model.systemMockEnabled)
        assertTrue(model.status.contains("Hook"))
        assertTrue(model.detail.contains("蜂窝/Wi-Fi"))
        assertTrue(model.detail.contains("刷新周期"))
        assertEquals("50.450100, 30.523400", model.effectiveCoordinate)
    }

    @Test
    fun `running system mock shows exact effective profile coordinate`() {
        val config = MockLocationConfig(50.4501, 30.5234)
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.SYSTEM_MOCK,
            MockProviderState.Running(config, emittedCount = 9),
            published(),
        )

        assertTrue(model.systemMockEnabled)
        assertTrue(model.status.contains("运行中"))
        assertTrue(model.status.contains("9"))
        assertEquals("50.450100, 30.523400", model.effectiveCoordinate)
    }

    @Test
    fun `failure reason stays visible and invalid profile is not presented as zero zero`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Failed("mock location app-op denied"),
            published(fields = emptyMap()),
        )

        assertTrue(model.status.contains("mock location app-op denied"))
        assertEquals("生效中档案未配置有效经纬度", model.effectiveCoordinate)
    }

    private fun published(
        fields: Map<String, String> = mapOf(
            "latitude" to "50.4501",
            "longitude" to "30.5234",
        ),
    ) = PublishedConfig(
        schemaVersion = 4,
        mode = "always_on",
        fields = fields,
        locationDeliveryMode = "hook",
    )
}
