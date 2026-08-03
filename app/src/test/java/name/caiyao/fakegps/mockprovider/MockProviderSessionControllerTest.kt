package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderSessionControllerTest {

    @Test
    fun `start removes stale provider then registers and publishes immediately`() {
        val gateway = RecordingMockProviderGateway()
        val states = mutableListOf<MockProviderState>()
        val controller = MockProviderSessionController(gateway) { states += it }
        val config = MockLocationConfig(50.4501, 30.5234)

        controller.start(config)

        assertEquals(listOf("remove", "replace", "publish:$config"), gateway.calls)
        assertEquals(MockProviderState.Running(config, emittedCount = 1), controller.state)
        assertEquals(
            listOf(
                MockProviderState.Starting(config),
                MockProviderState.Running(config, emittedCount = 1),
            ),
            states,
        )
    }

    @Test
    fun `fresh controller stop still removes a provider orphaned by a dead process`() {
        val gateway = RecordingMockProviderGateway()
        val states = mutableListOf<MockProviderState>()
        val controller = MockProviderSessionController(gateway) { states += it }

        controller.stop()

        assertEquals(listOf("remove"), gateway.calls)
        assertEquals(MockProviderState.Idle, controller.state)
        assertEquals(listOf(MockProviderState.Stopping, MockProviderState.Idle), states)
    }

    @Test
    fun `publish failure performs best effort cleanup and remains visible`() {
        val gateway = RecordingMockProviderGateway(failAt = "publish")
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        assertEquals(listOf("remove", "replace", "publish", "remove"), gateway.calls)
        assertTrue(controller.state is MockProviderState.Failed)
    }
}

internal class RecordingMockProviderGateway(
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
