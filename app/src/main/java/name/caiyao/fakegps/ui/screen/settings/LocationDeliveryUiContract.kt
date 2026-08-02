package name.caiyao.fakegps.ui.screen.settings

import java.util.Locale
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockProviderState

data class LocationDeliveryUiModel(
    val systemMockEnabled: Boolean,
    val status: String,
    val detail: String,
    val effectiveCoordinate: String,
)

object LocationDeliveryUiContract {
    fun model(
        mode: LocationDeliveryMode,
        providerState: MockProviderState,
        published: PublishedConfig?,
    ): LocationDeliveryUiModel {
        val status = when (providerState) {
            MockProviderState.Idle -> when (mode) {
                LocationDeliveryMode.HOOK -> "Hook 位置注入"
                LocationDeliveryMode.SYSTEM_MOCK -> "等待 System Mock 服务恢复"
            }
            is MockProviderState.Starting -> "System Mock 启动中"
            is MockProviderState.Running ->
                "System Mock 运行中 · ${providerState.emittedCount} 次"
            MockProviderState.Stopping -> "正在停止 System Mock"
            is MockProviderState.Failed -> "失败 · ${providerState.message}"
        }
        val latitude = published?.fields?.get("latitude")?.toDoubleOrNull()
        val longitude = published?.fields?.get("longitude")?.toDoubleOrNull()
        val coordinate = if (latitude != null && longitude != null) {
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        } else {
            "生效中档案未配置有效经纬度"
        }

        return LocationDeliveryUiModel(
            systemMockEnabled = mode == LocationDeliveryMode.SYSTEM_MOCK,
            status = status,
            detail =
                "此开关只选择位置交付方式；蜂窝/Wi-Fi 等档案字段仍由 Hook 提供。" +
                    "切回 Hook 后，已运行目标进程会在当前刷新周期内读取新模式。",
            effectiveCoordinate = coordinate,
        )
    }
}
