package name.caiyao.fakegps.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.SpoofSettings

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SpoofSettings.getInstance(app)

    val spoofMode: StateFlow<String> = settings.spoofMode
    val activeHourStart: StateFlow<Int> = settings.activeHourStart
    val activeHourEnd: StateFlow<Int> = settings.activeHourEnd

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

    private fun publish() = ConfigPrefsSync.sync(getApplication())
}
