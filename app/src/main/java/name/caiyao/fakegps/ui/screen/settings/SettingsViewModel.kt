package name.caiyao.fakegps.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import name.caiyao.fakegps.data.SpoofSettings

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SpoofSettings.getInstance(app)

    val spoofMode: StateFlow<String> = settings.spoofMode
    val activeHourStart: StateFlow<Int> = settings.activeHourStart
    val activeHourEnd: StateFlow<Int> = settings.activeHourEnd

    fun setSpoofMode(mode: String) = settings.setSpoofMode(mode)
    fun setActiveHourStart(hour: Int) = settings.setActiveHourStart(hour)
    fun setActiveHourEnd(hour: Int) = settings.setActiveHourEnd(hour)
}
