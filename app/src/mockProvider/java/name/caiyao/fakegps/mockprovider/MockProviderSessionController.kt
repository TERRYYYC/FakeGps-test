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
) {
    var state: MockProviderState = MockProviderState.Idle
        private set

    fun start(config: MockLocationConfig) {
        state = MockProviderState.Starting(config)
        transition(
            sideEffect = {
                // remove-before-add recovers a provider left behind by an interrupted prior run.
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
        state = MockProviderState.Stopping
        transition(
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
            state = success
        } catch (failure: Throwable) {
            val cleanupFailure = runCatching(gateway::removeGpsProvider).exceptionOrNull()
            val primary = failure.message ?: failure.javaClass.simpleName
            val cleanup = cleanupFailure?.let {
                "; cleanup failed: ${it.message ?: it.javaClass.simpleName}"
            }.orEmpty()
            state = MockProviderState.Failed(primary + cleanup)
        }
    }
}
