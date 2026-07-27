package name.caiyao.fakegps.ui.screen.verify

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.verify.DeviceObserver
import name.caiyao.fakegps.verify.HookApplicability
import name.caiyao.fakegps.verify.ObservationScope
import name.caiyao.fakegps.verify.VerificationEngine
import name.caiyao.fakegps.verify.VerificationReport
import java.util.Calendar

sealed interface PayloadStatus {
    /** Nothing has ever been published — the hook has no config to apply. */
    data object NeverPublished : PayloadStatus

    /** A payload exists but cannot be parsed; the hook keeps its last-known-good config. */
    data object Malformed : PayloadStatus

    data class Ok(val schemaVersion: Int, val fieldCount: Int) : PayloadStatus {
        /** The hook rejects a version it does not recognise rather than mis-reading the payload. */
        val compatible: Boolean get() = schemaVersion == ConfigPrefsSync.SCHEMA_VERSION
    }
}

data class VerifyUiState(
    val loading: Boolean = true,
    val scope: ObservationScope = ObservationScope.REAL_BASELINE,
    val payload: PayloadStatus = PayloadStatus.NeverPublished,
    val mode: String = "always_on",
    val applicability: HookApplicability = HookApplicability.APPLYING,
    val fingerprint: String? = null,
    val report: VerificationReport? = null,
    val notes: List<String> = emptyList(),
    val cellCount: Int = 0,
)

class VerifyViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true)
            try {
                collect()
            } catch (t: Throwable) {
                // viewModelScope installs no exception handler, so an escaping throwable takes the
                // process down; and without the finally the spinner would never clear while the
                // retry button stays disabled on `loading`. Neither is acceptable on the screen
                // whose entire job is to report what went wrong.
                _state.value = _state.value.copy(
                    loading = false,
                    notes = listOf("读取设备信息时出错：${t.javaClass.simpleName}: ${t.message}"),
                )
            } finally {
                if (_state.value.loading) _state.value = _state.value.copy(loading = false)
            }
        }
    }

    private fun collect() {
            val ctx = getApplication<Application>()
            val raw = ConfigPrefsSync.readPublishedRaw(ctx)
            val parsed = PublishedConfig.parse(raw)

            val payloadStatus = when {
                raw == null -> PayloadStatus.NeverPublished
                parsed == null -> PayloadStatus.Malformed
                else -> PayloadStatus.Ok(parsed.schemaVersion, parsed.fields.size)
            }

            val observation = DeviceObserver(ctx).observe()

            // The same readings feed different columns depending on what they can prove. Under
            // SELF_HOOKED they are observations that confirm or refute the config; under
            // REAL_BASELINE they are untouched device values, so every configured field is honestly
            // reported as "no evidence" instead of as a failure.
            val scope = ObservationScope.current()
            val selfHooked = scope == ObservationScope.SELF_HOOKED
            val report = VerificationEngine.buildReport(
                configured = parsed?.fields.orEmpty(),
                observed = if (selfHooked) observation.values else emptyMap(),
                baseline = if (selfHooked) emptyMap() else observation.values,
            )

            _state.value = VerifyUiState(
                loading = false,
                scope = scope,
                payload = payloadStatus,
                mode = parsed?.mode ?: "always_on",
                applicability = HookApplicability.of(
                    mode = parsed?.mode ?: "always_on",
                    schemaVersion = parsed?.schemaVersion ?: ConfigPrefsSync.SCHEMA_VERSION,
                    currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                    activeStart = parsed?.activeHourStart,
                    activeEnd = parsed?.activeHourEnd,
                ),
                fingerprint = raw?.let { PublishedConfig.fingerprint(it) },
                report = report,
                notes = observation.notes,
                cellCount = observation.cellCount,
            )
    }
}
