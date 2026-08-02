package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderServiceContractTest {

    @Test
    fun `null restart intent stops without resurrecting a session`() {
        assertEquals(
            MockProviderCommand.StopAfterProcessRecreation,
            MockProviderServiceContract.decode(action = null, latitude = null, longitude = null),
        )
    }

    @Test
    fun `explicit stop never requires coordinate extras`() {
        assertEquals(
            MockProviderCommand.Stop,
            MockProviderServiceContract.decode(
                action = MockProviderServiceContract.ACTION_STOP,
                latitude = null,
                longitude = null,
            ),
        )
    }

    @Test
    fun `start requires a complete valid coordinate`() {
        val valid = MockProviderServiceContract.decode(
            action = MockProviderServiceContract.ACTION_START,
            latitude = 40.7128,
            longitude = -74.0060,
            accuracyMeters = 4f,
        )
        val missing = MockProviderServiceContract.decode(
            action = MockProviderServiceContract.ACTION_START,
            latitude = null,
            longitude = -74.0060,
        )
        val invalid = MockProviderServiceContract.decode(
            action = MockProviderServiceContract.ACTION_START,
            latitude = 100.0,
            longitude = -74.0060,
        )

        assertEquals(
            MockProviderCommand.Start(MockLocationConfig(40.7128, -74.0060, 4f)),
            valid,
        )
        assertTrue(missing is MockProviderCommand.Rejected)
        assertTrue(invalid is MockProviderCommand.Rejected)
    }

    @Test
    fun `provider registration uses modern properties from Android 12`() {
        assertEquals(ProviderApiFamily.Legacy, ProviderApiFamily.forSdk(30))
        assertEquals(ProviderApiFamily.Modern, ProviderApiFamily.forSdk(31))
        assertEquals(ProviderApiFamily.Modern, ProviderApiFamily.forSdk(35))
    }

    @Test
    fun `sample factory always produces a complete gps sample`() {
        val sample = MockLocationSampleFactory(
            wallClockMillis = { 1_725_000_000_000L },
            elapsedRealtimeNanos = { 123_456_789L },
        ).create(MockLocationConfig(35.6812, 139.7671, 3f))

        assertEquals("gps", sample.provider)
        assertEquals(35.6812, sample.latitude, 0.0)
        assertEquals(139.7671, sample.longitude, 0.0)
        assertEquals(3f, sample.accuracyMeters)
        assertEquals(1_725_000_000_000L, sample.timeMillis)
        assertEquals(123_456_789L, sample.elapsedRealtimeNanos)
        assertTrue(sample.timeMillis > 0)
        assertTrue(sample.elapsedRealtimeNanos > 0)
    }
}
