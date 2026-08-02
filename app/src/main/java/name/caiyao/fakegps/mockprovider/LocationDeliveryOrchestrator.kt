package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode

/**
 * Orders provider state and the persisted Hook/System-Mock intent as one product transition.
 * Android bindings live in the service; this class stays pure so crash-window decisions are tested.
 */
class LocationDeliveryOrchestrator(
    private val controller: MockProviderSessionController,
    private val readPublished: () -> PublishedConfig?,
    private val readMode: () -> LocationDeliveryMode,
    private val persistMode: (LocationDeliveryMode) -> Boolean,
    private val publishConfig: () -> Boolean,
    private val persistCleanupRequired: (Boolean) -> Boolean,
) {
    fun enable(): MockProviderState {
        val resolution = EffectiveMockLocationResolver.resolve(readPublished())
        val ready = resolution as? EffectiveMockLocationResolution.Ready
            ?: return MockProviderState.Failed(
                (resolution as EffectiveMockLocationResolution.Invalid).message,
            )

        // Durable before the first system mutation: if the process dies after addTestProvider but
        // before mode publication, the next app launch still knows cleanup is required.
        if (!persistCleanupRequired(true)) {
            return MockProviderState.Failed("无法保存 Mock Provider 恢复标记")
        }

        controller.start(ready.config)
        if (controller.state !is MockProviderState.Running) return controller.state

        if (!persistMode(LocationDeliveryMode.SYSTEM_MOCK)) {
            controller.stop()
            if (controller.state is MockProviderState.Idle) persistCleanupRequired(false)
            return MockProviderState.Failed("无法保存 System Mock 位置模式")
        }
        if (!publishConfig()) {
            persistMode(LocationDeliveryMode.HOOK)
            publishConfig()
            controller.stop()
            if (controller.state is MockProviderState.Idle) persistCleanupRequired(false)
            return MockProviderState.Failed("System Mock 已回滚：无法发布 Hook 位置旁路配置")
        }
        return controller.state
    }

    fun disable(): MockProviderState {
        val persisted = persistMode(LocationDeliveryMode.HOOK)
        val published = persisted && publishConfig()
        controller.stop()

        if (controller.state is MockProviderState.Failed) return controller.state
        if (!persistCleanupRequired(false)) {
            return MockProviderState.Failed("GPS 已停止，但无法清除 Mock Provider 恢复标记")
        }
        if (!persisted) return MockProviderState.Failed("GPS 已停止，但无法保存 Hook 位置模式")
        if (!published) return MockProviderState.Failed("GPS 已停止，但 Hook 配置发布失败")
        return MockProviderState.Idle
    }

    fun refresh(): MockProviderState {
        if (readMode() != LocationDeliveryMode.SYSTEM_MOCK) return disable()

        val resolution = EffectiveMockLocationResolver.resolve(readPublished())
        val ready = resolution as? EffectiveMockLocationResolution.Ready
        if (ready == null) {
            val reason = (resolution as EffectiveMockLocationResolution.Invalid).message
            val stopped = disable()
            return if (stopped is MockProviderState.Failed) stopped else MockProviderState.Failed(reason)
        }

        val running = controller.state as? MockProviderState.Running
        if (running?.config == ready.config) {
            controller.tick()
        } else {
            controller.start(ready.config)
        }
        return controller.state
    }

    /** Best-effort provider cleanup without changing persisted user intent. */
    fun cleanupRuntimeOnly(): MockProviderState {
        controller.stop()
        return controller.state
    }

}
