package name.caiyao.fakegps.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences wrapper for spoof configuration.
 * Read by UI (Compose) and exposed to hooks via AppInfoProvider.
 */
class SpoofSettings private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "spoof_settings"

        const val KEY_SPOOF_MODE = "spoof_mode"
        const val KEY_ACTIVE_HOUR_START = "active_hour_start"
        const val KEY_ACTIVE_HOUR_END = "active_hour_end"

        /** Mode values — also used by MainHook when reading from ContentProvider */
        const val MODE_ALWAYS_ON = "always_on"
        const val MODE_TIME_BASED = "time_based"
        const val MODE_OFF = "off"

        @Volatile
        private var INSTANCE: SpoofSettings? = null

        fun getInstance(context: Context): SpoofSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpoofSettings(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { INSTANCE = it }
            }
        }
    }

    private val _spoofMode = MutableStateFlow(prefs.getString(KEY_SPOOF_MODE, MODE_ALWAYS_ON)!!)
    val spoofMode: StateFlow<String> = _spoofMode

    private val _activeHourStart = MutableStateFlow(prefs.getInt(KEY_ACTIVE_HOUR_START, 7))
    val activeHourStart: StateFlow<Int> = _activeHourStart

    private val _activeHourEnd = MutableStateFlow(prefs.getInt(KEY_ACTIVE_HOUR_END, 22))
    val activeHourEnd: StateFlow<Int> = _activeHourEnd

    fun setSpoofMode(mode: String) {
        prefs.edit().putString(KEY_SPOOF_MODE, mode).apply()
        _spoofMode.value = mode
    }

    fun setActiveHourStart(hour: Int) {
        prefs.edit().putInt(KEY_ACTIVE_HOUR_START, hour.coerceIn(0, 23)).apply()
        _activeHourStart.value = hour.coerceIn(0, 23)
    }

    fun setActiveHourEnd(hour: Int) {
        prefs.edit().putInt(KEY_ACTIVE_HOUR_END, hour.coerceIn(0, 23)).apply()
        _activeHourEnd.value = hour.coerceIn(0, 23)
    }

    /** Read raw prefs — used by AppInfoProvider (no Flow needed). */
    fun getRawMode(): String = prefs.getString(KEY_SPOOF_MODE, MODE_ALWAYS_ON)!!
    fun getRawHourStart(): Int = prefs.getInt(KEY_ACTIVE_HOUR_START, 7)
    fun getRawHourEnd(): Int = prefs.getInt(KEY_ACTIVE_HOUR_END, 22)
}
