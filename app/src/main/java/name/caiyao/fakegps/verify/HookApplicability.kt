package name.caiyao.fakegps.verify

import name.caiyao.fakegps.config.ConfigPrefsSync

/**
 * Whether the hook is actually applying the published payload right now.
 *
 * A verdict is only meaningful once this says [APPLYING]. In every other state the hook passes real
 * values through BY DESIGN, so each configured field reads back real — which, scored naively, looks
 * identical to a broken hook. The screen would then tell the user to go re-check their module scope
 * while the module behaves exactly as configured.
 *
 * Deliberately mirrors the gates in MainHook#loadSnapshot.
 */
enum class HookApplicability {
    /** Hook is live and applying this payload. Verdicts mean what they say. */
    APPLYING,

    /** 伪装模式 = 关闭. */
    MODE_OFF,

    /** time_based, and the current hour falls outside the configured window. */
    OUTSIDE_ACTIVE_HOURS,

    /** The hook refuses a payload version it cannot interpret and keeps last-known-good instead. */
    SCHEMA_REJECTED;

    val verdictsMeaningful: Boolean get() = this == APPLYING

    companion object {
        fun of(
            mode: String,
            schemaVersion: Int,
            currentHour: Int,
            activeStart: Int? = null,
            activeEnd: Int? = null,
        ): HookApplicability {
            // Checked first: a rejected payload never loads at all, so its mode and hours are moot.
            if (schemaVersion != ConfigPrefsSync.SCHEMA_VERSION) return SCHEMA_REJECTED
            if (mode == "off") return MODE_OFF
            if (mode == "time_based" && activeStart != null && activeEnd != null) {
                val inRange = if (activeStart <= activeEnd) {
                    currentHour >= activeStart && currentHour < activeEnd
                } else {
                    // A window wrapping past midnight, e.g. 22:00-07:00.
                    currentHour >= activeStart || currentHour < activeEnd
                }
                if (!inRange) return OUTSIDE_ACTIVE_HOURS
            }
            return APPLYING
        }
    }
}
