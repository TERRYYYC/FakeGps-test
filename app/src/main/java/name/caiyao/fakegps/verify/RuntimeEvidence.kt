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
}
