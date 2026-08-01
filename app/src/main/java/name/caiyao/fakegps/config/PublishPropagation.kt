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
     * What the stored publish timestamp must become when publication fails: absent.
     *
     * Lives here, not on ConfigPrefsSync, for two reasons: this object is free of Android types so
     * the rule is reachable from a JVM test, and the rule belongs to the propagation contract rather
     * than to the writer.
     *
     * Crucially the failing path must CLEAR the key, not merely skip writing it — SharedPreferences
     * is persistent, so skipping leaves the timestamp from the last SUCCESSFUL publish on disk, and
     * a subsequent unreadable payload would borrow that still-open window.
     */
    fun timestampOnFailedPublish(): Long? = null

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
