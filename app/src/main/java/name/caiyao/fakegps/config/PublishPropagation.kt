package name.caiyao.fakegps.config

/**
 * How long a freshly published config may take to reach the hook.
 *
 * [ConfigPrefsSync.sync] writes the payload synchronously on every save, but MainHook re-reads the
 * prefs file on a periodic timer and keeps serving the PREVIOUS Snapshot until it fires. Verifying
 * inside that window compares a brand-new config against values produced by the old one, so every
 * changed field reads back "wrong".
 *
 * That matters because saving a profile is precisely what a user does immediately before opening
 * the verify screen — so the false red is not a rare race, it is the default first experience.
 */
object PublishPropagation {

    /** Mirrors MainHook's refresh cadence: `handler.sendEmptyMessageDelayed(1, 30 * 1000)`. */
    const val HOOK_REFRESH_INTERVAL_MS = 30_000L

    /**
     * Whether the hook may still be running the previous config.
     *
     * Returns false whenever the answer is not knowable (no recorded publish time) or the clock is
     * inconsistent (timestamp in the future): suppressing a genuine failure indefinitely is a worse
     * outcome than showing one spurious red, so this only ever softens a verdict inside a bounded,
     * provable window.
     */
    fun isPending(publishedAtMs: Long?, nowMs: Long): Boolean {
        if (publishedAtMs == null) return false
        val elapsed = nowMs - publishedAtMs
        if (elapsed < 0) return false
        return elapsed < HOOK_REFRESH_INTERVAL_MS
    }
}
