package name.caiyao.fakegps.verify

/** Stable, value-free release evidence consumed by the host acceptance harness. */
object RuntimeEvidence {
    const val PROBE_TAG = "FakeGPS-Probe"
    private const val HOOK_PREFIX = "FakeGPS-Hook:"

    @JvmStatic
    fun probeRequested(request: ProbeRequest): String =
        "event=requested requestId=${request.requestId} fp=${request.fingerprint}"

    @JvmStatic
    fun probeStarted(request: ProbeRequest): String =
        "event=started requestId=${request.requestId} fp=${request.fingerprint}"

    @JvmStatic
    fun probeDelivered(request: ProbeRequest, fields: Int): String =
        "event=delivered requestId=${request.requestId} fp=${request.fingerprint} fields=$fields"

    @JvmStatic
    fun probeFailed(request: ProbeRequest, failure: ProbeFailure): String =
        "event=failed requestId=${request.requestId} fp=${request.fingerprint} reason=${failure.name}"

    @JvmStatic
    fun probeIgnored(observed: ProbeRequest): String =
        "event=ignored requestId=${observed.requestId} fp=${observed.fingerprint} reason=STALE_RESULT"

    @JvmStatic
    fun schedulerOwned(process: String, intervalMs: Long): String =
        "$HOOK_PREFIX event=scheduler_owned process=$process intervalMs=$intervalMs"

    @JvmStatic
    fun intervalChanged(process: String, fromMs: Long, toMs: Long): String =
        "$HOOK_PREFIX event=interval_changed process=$process fromMs=$fromMs toMs=$toMs"

    // ---- Phase B: event-driven refresh evidence ----

    @JvmStatic
    fun observerArmed(process: String, dirPath: String): String =
        "$HOOK_PREFIX event=observer_armed process=$process dir=$dirPath"

    @JvmStatic
    fun observerArmFailed(process: String, error: String): String =
        "$HOOK_PREFIX event=observer_arm_failed process=$process error=$error"

    @JvmStatic
    fun timerFallback(process: String, intervalMs: Long): String =
        "$HOOK_PREFIX event=timer_fallback process=$process intervalMs=$intervalMs"

    // ---- Fused client runtime discovery evidence (2026-08-04 design) ----

    @JvmStatic
    fun fusedFactoryArmed(overloads: Int): String =
        "$HOOK_PREFIX event=fused_factory_armed overloads=$overloads"

    @JvmStatic
    fun fusedClientDiscovered(runtimeClass: String, loaderId: Int): String =
        "$HOOK_PREFIX event=fused_client_discovered class=$runtimeClass loader=$loaderId"

    @JvmStatic
    fun fusedSurfaceHooked(surface: String, owner: String): String =
        "$HOOK_PREFIX event=fused_surface_hooked surface=$surface owner=$owner"

    @JvmStatic
    fun fusedClientRejected(reason: String): String =
        "$HOOK_PREFIX event=fused_client_rejected reason=$reason"

    @JvmStatic
    fun fusedSurfaceMissing(method: String): String =
        "$HOOK_PREFIX event=fused_surface_missing method=$method"
}
