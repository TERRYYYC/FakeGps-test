package name.caiyao.fakegps.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.data.SpoofSettings

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SpoofSettings.getInstance(app)

    val spoofMode: StateFlow<String> = settings.spoofMode
    val activeHourStart: StateFlow<Int> = settings.activeHourStart
    val activeHourEnd: StateFlow<Int> = settings.activeHourEnd

    /** Hook refresh cadence, in seconds. Always a value [PublishPropagation] sanctions. */
    val refreshIntervalSec: StateFlow<Int> = settings.refreshIntervalSec

    /** The choices the picker may offer — from the policy, never from the screen. */
    val refreshIntervalChoicesSec: List<Int> = PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC

    /**
     * Every setting mutation must re-publish the transport payload (review FC-1).
     * Writing only to SpoofSettings leaves the hook side reading a stale snapshot until
     * the app restarts or a profile is edited — i.e. switching to `off` would NOT actually
     * stop spoofing in the target process.
     */
    fun setSpoofMode(mode: String) {
        settings.setSpoofMode(mode)
        publish()
    }

    fun setActiveHourStart(hour: Int) {
        settings.setActiveHourStart(hour)
        publish()
    }

    fun setActiveHourEnd(hour: Int) {
        settings.setActiveHourEnd(hour)
        publish()
    }

    /**
     * Changing the cadence must re-publish like any other setting: the interval is part of the
     * payload the hook reads, so persisting it without publishing would leave the hook running the
     * OLD cadence — the setting would appear to apply while changing nothing.
     */
    fun setRefreshIntervalSec(seconds: Int) {
        settings.setRefreshIntervalSec(seconds)
        publish()
    }

    private fun publish() = ConfigPrefsSync.sync(getApplication())
}
