package name.caiyao.fakegps.mockprovider

sealed interface MockProviderState {
    data object Idle : MockProviderState
    data class Starting(val config: MockLocationConfig) : MockProviderState
    data class Running(
        val config: MockLocationConfig,
        val emittedCount: Long,
    ) : MockProviderState
    data object Stopping : MockProviderState
    data class Failed(val message: String) : MockProviderState
}

class MockProviderSessionController(
    private val gateway: MockProviderGateway,
    private val onStateChanged: (MockProviderState) -> Unit = {},
) {
    var state: MockProviderState = MockProviderState.Idle
        private set

    fun start(config: MockLocationConfig) {
        updateState(MockProviderState.Starting(config))
        transition(
            sideEffect = {
                // System test providers can survive process death. Always repair stale state first.
                gateway.removeGpsProvider()
                gateway.replaceGpsProvider()
                gateway.publish(config)
            },
            success = MockProviderState.Running(config, emittedCount = 1),
        )
    }

    fun tick() {
        val running = state as? MockProviderState.Running ?: return
        transition(
            sideEffect = { gateway.publish(running.config) },
            success = running.copy(emittedCount = running.emittedCount + 1),
        )
    }

    fun stop() {
        updateState(MockProviderState.Stopping)
        transition(
            // Never short-circuit on in-memory Idle: a previous process may own the real residue.
            sideEffect = gateway::removeGpsProvider,
            success = MockProviderState.Idle,
        )
    }

    private fun transition(
        sideEffect: () -> Unit,
        success: MockProviderState,
    ) {
        try {
            sideEffect()
            updateState(success)
        } catch (failure: Throwable) {
            val cleanupFailure = runCatching(gateway::removeGpsProvider).exceptionOrNull()
            val primary = failure.message ?: failure.javaClass.simpleName
            val cleanup = cleanupFailure?.let {
                "; cleanup failed: ${it.message ?: it.javaClass.simpleName}"
            }.orEmpty()
            updateState(MockProviderState.Failed(primary + cleanup))
        }
    }

    private fun updateState(next: MockProviderState) {
        state = next
        onStateChanged(next)
    }
}
