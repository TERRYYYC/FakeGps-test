package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Per-delivery evidence policy: turns every hooked location delivery into BOUNDED,
 * value-free evidence.
 *
 * Why this exists: before it, the only fused evidence was install-time
 * (fused_surface_hooked / fused_delivery_summary), which fires once when listeners
 * register. That makes "no events in the last 60s" indistinguishable between
 * "hook delivered correctly and quietly" and "hook stopped delivering" — the exact
 * ambiguity that forced Issue #15's A/B matrix to a BLOCKED verdict.
 */
public class DeliveryEvidencePolicyTest {

    // ---- classification ----

    @Test
    public void incomingValueEqualToProfileIsClassifiedEqualsProfile() {
        assertEquals(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 50.45, 30.52));
    }

    @Test
    public void incomingValueDifferentFromProfileIsClassifiedNotEqual() {
        assertEquals(
                DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 37.77, -122.41));
    }

    @Test
    public void absentIncomingValueIsClassifiedNoObserved() {
        assertEquals(
                DeliveryEvidencePolicy.NO_OBSERVED,
                DeliveryEvidencePolicy.classify(50.45, 30.52, null, null));
        assertEquals(
                DeliveryEvidencePolicy.NO_OBSERVED,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 50.45, null));
    }

    @Test
    public void subMillimeterFloatDriftStillCountsAsProfile() {
        // createFakeLocation writes the profile doubles, but values round-trip through
        // float-backed Location accessors. Drift far below any real-vs-mock distance
        // must not masquerade as a revert.
        assertEquals(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 50.450000001, 30.520000001));
    }

    @Test
    public void classificationNeverEchoesACoordinate() {
        String c = DeliveryEvidencePolicy.classify(50.4512345, 30.5298765, 37.7749295, -122.4194155);
        assertEquals(DeliveryEvidencePolicy.NOT_EQUAL, c);
        // value-free contract: the classification is an enum-like token, never a payload.
        assertNotEquals(-1, "EQUALS_PROFILE|NOT_EQUAL|NO_OBSERVED".indexOf(c));
    }

    // ---- bounded emission gate ----

    @Test
    public void firstDeliveryAlwaysEmits() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        assertEquals(1, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 1_000L));
    }

    @Test
    public void steadyStateDeliveriesStaySilentUntilHeartbeat() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 0L);
        assertEquals(-1, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 1_000L));
        assertEquals(-1, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 29_999L));
    }

    @Test
    public void heartbeatEmitsAndReportsSuppressedDeliveryCount() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 0L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 10_000L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 20_000L);
        // 3 deliveries covered by this heartbeat: the two silent ones plus this one.
        assertEquals(3, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 30_000L));
    }

    @Test
    public void classificationChangeEmitsImmediatelyEvenInsideHeartbeatWindow() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 0L);
        // A revert must never wait for the heartbeat: this is the snap-back detector.
        assertEquals(1, p.record(DeliveryEvidencePolicy.NOT_EQUAL, 500L));
    }

    @Test
    public void countResetsAfterEachEmission() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 0L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 10_000L);
        assertEquals(2, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 30_000L));
        assertEquals(-1, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 31_000L));
        assertEquals(2, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 60_000L));
    }

    @Test
    public void separateSurfacesDoNotShareGateState() {
        DeliveryEvidencePolicy a = new DeliveryEvidencePolicy();
        DeliveryEvidencePolicy b = new DeliveryEvidencePolicy();
        assertEquals(1, a.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 0L));
        assertEquals(1, b.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 0L));
    }

    // ---- P2-1: the input classification is a SEPARATE, honestly named axis ----
    //
    // Review finding: classifying the pre-replacement input and labelling it
    // EQUALS_PROFILE/NOT_EQUAL inverts the verdict — a healthy interception (real input
    // replaced by the profile) reported NOT_EQUAL, which the A/B harness reads as a
    // snap-back. The delivered value is what the surface promises to describe; the input
    // is separately useful but must never borrow the delivery tokens.

    @Test
    public void interceptedRealInputIsAPositiveFactNotADeliveryFailure() {
        assertEquals(
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 37.77, -122.41));
    }

    @Test
    public void inputAlreadyMatchingProfileIsReportedSeparately() {
        assertEquals(
                DeliveryEvidencePolicy.INPUT_EQUALS_PROFILE,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 50.45, 30.52));
    }

    @Test
    public void absentInputIsItsOwnTokenNotADeliveryToken() {
        assertEquals(
                DeliveryEvidencePolicy.INPUT_ABSENT,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, null, null));
    }

    @Test
    public void inputTokensNeverCollideWithDeliveryTokens() {
        // The whole defect was one vocabulary serving two questions.
        assertNotEquals(
                DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 37.77, -122.41));
        assertNotEquals(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 50.45, 30.52));
    }

    // ---- P3: heartbeat must not depend on a settable wall clock ----

    @Test
    public void clockGoingBackwardsStillHeartbeats() {
        // System time sync / manual change can move wall clock backwards. With a plain
        // (now - last >= HEARTBEAT) test that stays negative for as long as the jump,
        // healthy deliveries would go silent and re-create the exact "silence == stopped"
        // ambiguity this policy exists to remove.
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 1_000_000L);
        assertEquals(1, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 5_000L));
    }

    @Test
    public void backwardsJumpRebasesTheWindowInsteadOfLatchingOpen() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 1_000_000L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 5_000L);
        // After rebasing, normal suppression resumes; it must not emit on every delivery.
        assertEquals(-1, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 6_000L));
        assertEquals(2, p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, 35_000L));
    }
}
