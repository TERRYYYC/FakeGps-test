package name.caiyao.fakegps.hook;

/**
 * Turns every hooked location delivery into BOUNDED, value-free evidence.
 *
 * <p>Before this policy the only fused evidence was install-time
 * ({@code fused_surface_hooked}, {@code fused_delivery_summary}), emitted once when a
 * listener registers. That makes "no events for 60s" ambiguous between "the hook is
 * delivering correctly and quietly" and "the hook stopped delivering" — the ambiguity
 * that forced Issue #15's Maps A/B matrix to a BLOCKED verdict: continuous-update,
 * recenter and foreground/background stability were unobservable.
 *
 * <p>Two rules keep the evidence useful without turning a per-fix callback into a log
 * firehose:
 * <ul>
 *   <li><b>edge-triggered</b> — a classification change is emitted immediately, so a
 *       revert to the real position is never hidden behind a timer;</li>
 *   <li><b>heartbeat</b> — an unchanged classification is re-emitted at most once per
 *       {@link #HEARTBEAT_MS}, carrying the number of deliveries it covers, so silence
 *       means "not delivering" instead of "nothing to say".</li>
 * </ul>
 *
 * <p>Deliberately Android-free: it takes primitive coordinates rather than
 * {@code android.location.Location} so the whole policy is executable under plain JUnit
 * (the project ships no Robolectric, and {@code new Location(...)} throws {@code Stub!}).
 * It never stores or returns a coordinate — only one of three fixed tokens.
 */
final class DeliveryEvidencePolicy {

    /**
     * Delivery axis: what the target app actually received. These tokens describe the
     * OUTGOING value only.
     */
    static final String EQUALS_PROFILE = "EQUALS_PROFILE";
    static final String NOT_EQUAL = "NOT_EQUAL";
    static final String NO_OBSERVED = "NO_OBSERVED";

    /**
     * Interception axis: what the app would have received without the hook. Deliberately a
     * DISJOINT vocabulary.
     *
     * <p>The first cut reused the delivery tokens for this question, which inverted the
     * verdict: a healthy interception (real update displaced by the profile) emitted
     * NOT_EQUAL, and the acceptance harness reads NOT_EQUAL as a snap-back. Differing input
     * is a positive fact — it is the proof the hook is doing work — so it gets a name that
     * cannot be mistaken for a delivery failure.
     */
    static final String INPUT_EQUALS_PROFILE = "INPUT_EQUALS_PROFILE";
    static final String INPUT_DIFFERS_PROFILE = "INPUT_DIFFERS_PROFILE";
    static final String INPUT_ABSENT = "INPUT_ABSENT";

    /**
     * Sub-millimetre at any latitude: comfortably above the drift of round-tripping a
     * double through float-backed Location accessors, and orders of magnitude below any
     * real-versus-spoof separation we could ever need to distinguish.
     */
    private static final double EPSILON_DEG = 1e-6;

    static final long HEARTBEAT_MS = 30_000L;

    private String lastEmitted;
    private long lastEmitMs;
    private int pending;

    /**
     * Classify the value a delivery was about to carry against the active profile.
     * Returns a fixed token; coordinates are consumed, never retained or echoed.
     */
    static String classify(Double expectedLat, Double expectedLon,
                           Double observedLat, Double observedLon) {
        if (expectedLat == null || expectedLon == null) return NO_OBSERVED;
        if (observedLat == null || observedLon == null) return NO_OBSERVED;
        boolean same = Math.abs(expectedLat - observedLat) <= EPSILON_DEG
                && Math.abs(expectedLon - observedLon) <= EPSILON_DEG;
        return same ? EQUALS_PROFILE : NOT_EQUAL;
    }

    /**
     * Classify what the delivery would have carried WITHOUT the hook. Separate vocabulary
     * from {@link #classify} on purpose — see the INPUT_* constants.
     */
    static String classifyInput(Double expectedLat, Double expectedLon,
                                Double incomingLat, Double incomingLon) {
        if (expectedLat == null || expectedLon == null) return INPUT_ABSENT;
        if (incomingLat == null || incomingLon == null) return INPUT_ABSENT;
        boolean same = Math.abs(expectedLat - incomingLat) <= EPSILON_DEG
                && Math.abs(expectedLon - incomingLon) <= EPSILON_DEG;
        return same ? INPUT_EQUALS_PROFILE : INPUT_DIFFERS_PROFILE;
    }

    /**
     * Record one delivery on this surface.
     *
     * @return the number of deliveries the resulting evidence line covers, or {@code -1}
     *         when this delivery is intentionally silent.
     */
    synchronized int record(String classification, long nowMs) {
        pending++;
        boolean first = lastEmitted == null;
        boolean changed = !first && !lastEmitted.equals(classification);
        // A clock that moved backwards would leave (now - last) negative for as long as the
        // jump, muting healthy deliveries and re-creating the "silence == stopped" ambiguity
        // this policy exists to remove. Callers pass a monotonic clock; treating a backwards
        // step as heartbeat-due keeps the guarantee even if one does not, and rebases the
        // window so suppression resumes immediately afterwards rather than latching open.
        long elapsed = nowMs - lastEmitMs;
        boolean heartbeat = !first && (elapsed < 0 || elapsed >= HEARTBEAT_MS);
        if (first || changed || heartbeat) {
            int covered = pending;
            pending = 0;
            lastEmitted = classification;
            lastEmitMs = nowMs;
            return covered;
        }
        return -1;
    }
}
