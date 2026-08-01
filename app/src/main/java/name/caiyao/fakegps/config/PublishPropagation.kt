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

    // ---------------------------------------------------------------------------------------
    // Refresh interval policy
    //
    // The Settings screen used to render a hardcoded literal "60 秒" next to a row with no
    // clickable modifier: a number that was both wrong (the hook re-reads every 30 s) and
    // unchangeable. A screen stating a cadence the runtime does not use is the same class of
    // defect as a verify screen reporting a spoof that was never applied — the UI lies.
    //
    // The cure is not a corrected literal but a single source: the picker's choices, its
    // default, and the pending window below all resolve HERE, so a screen physically cannot
    // display a cadence the policy does not sanction. This object is deliberately free of
    // Android types, so the hook side can share it verbatim.
    // ---------------------------------------------------------------------------------------

    /** Seconds; the cadence used when nothing has been configured. Equals the hook's own default. */
    const val DEFAULT_REFRESH_INTERVAL_SEC = 30

    /**
     * The intervals a user may pick, ascending.
     *
     * Bounded on both ends on purpose: below ~5 s the periodic prefs re-read stops being free
     * (the runtime currently re-reads more than once per cycle per process — see the duplicate
     * scheduler finding), and beyond a minute the "did my change apply?" feedback loop becomes
     * indistinguishable from a broken hook, which is exactly the confusion this setting exists
     * to remove.
     */
    val REFRESH_INTERVAL_CHOICES_SEC: List<Int> = listOf(5, 10, 15, 30, 60)

    /** Whether [seconds] is a cadence the hook is allowed to run at. */
    fun isValidInterval(seconds: Int): Boolean = seconds in REFRESH_INTERVAL_CHOICES_SEC

    /**
     * Coerce a stored/incoming value into a cadence the hook can honour.
     *
     * Fails towards the DEFAULT rather than towards "no refresh": a corrupt preference must not be
     * able to silently freeze the hook on a stale snapshot, which would present exactly like the
     * "I changed the config and nothing happened" failure this feature addresses.
     */
    fun sanitizeInterval(seconds: Int): Int =
        if (isValidInterval(seconds)) seconds else DEFAULT_REFRESH_INTERVAL_SEC

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
    fun isPending(publishedAtMs: Long?, nowMs: Long): Boolean =
        isPending(publishedAtMs, nowMs, DEFAULT_REFRESH_INTERVAL_SEC)

    /**
     * [isPending] against the CONFIGURED cadence.
     *
     * Once the interval is user-selectable the window must track it: computing "may still be
     * pending" from a frozen 30 s while the hook actually re-reads every 60 s would resurrect the
     * false-red this whole mechanism exists to suppress — and at 5 s it would keep excusing a
     * genuinely failed publish for 25 s longer than warranted.
     */
    fun isPending(publishedAtMs: Long?, nowMs: Long, refreshIntervalSec: Int): Boolean {
        if (publishedAtMs == null) return false
        val elapsed = nowMs - publishedAtMs
        if (elapsed < 0) return false
        return elapsed < sanitizeInterval(refreshIntervalSec) * 1000L
    }
}
