package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderUiContractTest {

    @Test
    fun `valid form becomes a session config`() {
        val result = MockProviderForm(
            latitude = "40.7128",
            longitude = "-74.0060",
            accuracyMeters = "3",
        ).validate()

        assertEquals(
            MockProviderFormResult.Valid(MockLocationConfig(40.7128, -74.0060, 3f)),
            result,
        )
    }

    @Test
    fun `invalid form explains the field instead of starting`() {
        val missing = MockProviderForm(latitude = "", longitude = "10").validate()
        val outOfRange = MockProviderForm(latitude = "91", longitude = "10").validate()
        val badAccuracy = MockProviderForm(
            latitude = "10",
            longitude = "10",
            accuracyMeters = "0",
        ).validate()

        assertTrue((missing as MockProviderFormResult.Invalid).message.contains("Latitude"))
        assertTrue((outOfRange as MockProviderFormResult.Invalid).message.contains("latitude"))
        assertTrue((badAccuracy as MockProviderFormResult.Invalid).message.contains("accuracy"))
    }

    @Test
    fun `copy distinguishes the lab and preserves the restore instruction`() {
        assertEquals("FakeGPS Mock Provider Lab", MockProviderUiCopy.TITLE)
        assertTrue(MockProviderUiCopy.PERMISSION_GUIDANCE.contains("Developer options"))
        assertTrue(MockProviderUiCopy.RESTORE_GUIDANCE.contains("Fake GPS Location"))
    }

    @Test
    fun `visible status renders running count and failure reason`() {
        val config = MockLocationConfig(1.0, 2.0)

        assertEquals("Idle", MockProviderUiCopy.status(MockProviderState.Idle))
        assertEquals(
            "Running · 7 samples",
            MockProviderUiCopy.status(MockProviderState.Running(config, emittedCount = 7)),
        )
        assertEquals(
            "Failed · mock location app-op denied",
            MockProviderUiCopy.status(MockProviderState.Failed("mock location app-op denied")),
        )
    }
}
