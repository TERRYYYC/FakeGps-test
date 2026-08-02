package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderSessionControllerTest {

    @Test
    fun `config accepts geographic boundaries and rejects invalid samples`() {
        MockLocationConfig(latitude = -90.0, longitude = 180.0, accuracyMeters = 1f)
        MockLocationConfig(latitude = 90.0, longitude = -180.0, accuracyMeters = 50f)

        assertInvalid { MockLocationConfig(latitude = -90.0001, longitude = 0.0) }
        assertInvalid { MockLocationConfig(latitude = 90.0001, longitude = 0.0) }
        assertInvalid { MockLocationConfig(latitude = 0.0, longitude = -180.0001) }
        assertInvalid { MockLocationConfig(latitude = 0.0, longitude = 180.0001) }
        assertInvalid { MockLocationConfig(latitude = Double.NaN, longitude = 0.0) }
        assertInvalid { MockLocationConfig(latitude = 0.0, longitude = Double.POSITIVE_INFINITY) }
        assertInvalid { MockLocationConfig(latitude = 0.0, longitude = 0.0, accuracyMeters = 0f) }
    }

    @Test
    fun `start removes stale provider then registers and publishes immediately`() {
        val gateway = RecordingGateway()
        val controller = MockProviderSessionController(gateway)
        val config = MockLocationConfig(40.7128, -74.0060)

        controller.start(config)

        assertEquals(listOf("remove", "replace", "publish:$config"), gateway.calls)
        assertEquals(MockProviderState.Running(config, emittedCount = 1), controller.state)
    }

    @Test
    fun `tick only publishes while running and increments evidence count`() {
        val gateway = RecordingGateway()
        val controller = MockProviderSessionController(gateway)
        val config = MockLocationConfig(35.6812, 139.7671)

        controller.tick()
        controller.start(config)
        controller.tick()

        assertEquals(2, gateway.calls.count { it.startsWith("publish:") })
        assertEquals(MockProviderState.Running(config, emittedCount = 2), controller.state)
    }

    @Test
    fun `repeated start replaces the existing session with the newest config`() {
        val gateway = RecordingGateway()
        val controller = MockProviderSessionController(gateway)
        val first = MockLocationConfig(10.0, 20.0)
        val second = MockLocationConfig(30.0, 40.0)

        controller.start(first)
        controller.start(second)

        assertEquals(
            listOf(
                "remove", "replace", "publish:$first",
                "remove", "replace", "publish:$second",
            ),
            gateway.calls,
        )
        assertEquals(MockProviderState.Running(second, emittedCount = 1), controller.state)
    }

    @Test
    fun `stop is idempotent and leaves the controller idle`() {
        val gateway = RecordingGateway()
        val controller = MockProviderSessionController(gateway)

        controller.stop()
        controller.stop()

        assertEquals(listOf("remove", "remove"), gateway.calls)
        assertEquals(MockProviderState.Idle, controller.state)
    }

    @Test
    fun `publish failure performs best effort cleanup and remains visible`() {
        val gateway = RecordingGateway(failAt = "publish")
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(1.0, 2.0))

        assertEquals(listOf("remove", "replace", "publish", "remove"), gateway.calls)
        assertTrue(controller.state is MockProviderState.Failed)
    }

    private fun assertInvalid(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected IllegalArgumentException", failure is IllegalArgumentException)
    }
}

private class RecordingGateway(
    private val failAt: String? = null,
) : MockProviderGateway {
    val calls = mutableListOf<String>()

    override fun replaceGpsProvider() {
        calls += "replace"
        if (failAt == "replace") error("replace failed")
    }

    override fun publish(config: MockLocationConfig) {
        calls += if (failAt == "publish") "publish" else "publish:$config"
        if (failAt == "publish") error("publish failed")
    }

    override fun removeGpsProvider() {
        calls += "remove"
        if (failAt == "remove") error("remove failed")
    }
}
