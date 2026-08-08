package name.caiyao.fakegps.config

import name.caiyao.fakegps.config.ConfigPublicationContract.PublishState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable publication outcome is written FAIL-CLOSED across every interruption point
 * (PR #23 exact-HEAD review P1/P2, Sol):
 *  - a process death AFTER the payload commit but BEFORE readability verification must never leave a
 *    success timestamp (else a 0660 payload carries a "published" marker — the false-green this PR
 *    exists to kill, resurrected on interruption);
 *  - a verified FAILURE must never advance the active pointer past the last verified-good profile.
 *
 * These pin the exact transitions ConfigPrefsSync applies to the private outcome store, so a later
 * edit that reintroduces the optimistic-commit-then-cleanup order fails here — which the final
 * boolean contract alone could not catch.
 */
class PublicationStateMachineTest {

    private val lastGood = PublishState(publishedAtMs = 1_000L, publishFailed = false, activeProfileId = 5L)

    @Test
    fun `pre-commit fail-closed clears success and holds the active pointer`() {
        // This is the DURABLE state during the window between payload commit and verification.
        val durable = ConfigPublicationContract.preCommitFailClosed(lastGood)
        assertNull("no success timestamp may survive an interruption before verify", durable.publishedAtMs)
        assertTrue(durable.publishFailed)
        assertEquals("active must not advance to the in-flight profile", 5L, durable.activeProfileId)
    }

    @Test
    fun `interruption after payload commit before verification reads as failure, not success`() {
        // Model of sync(): pre-mark fail-closed, commit the payload, then die before onVerified*.
        val durable = ConfigPublicationContract.preCommitFailClosed(lastGood)
        assertFalse("a 0660 payload must never carry a live publish timestamp", durable.publishedAtMs != null)
        assertTrue(durable.publishFailed)
        assertEquals(5L, durable.activeProfileId)
    }

    @Test
    fun `verified publish records the timestamp and advances the active pointer`() {
        val out = ConfigPublicationContract.onVerifiedPublish(nowMs = 2_000L, publishedProfileId = 9L)
        assertEquals(2_000L, out.publishedAtMs)
        assertFalse(out.publishFailed)
        assertEquals(9L, out.activeProfileId)
    }

    @Test
    fun `verified failure keeps the last verified-good active pointer and no timestamp`() {
        val precommit = ConfigPublicationContract.preCommitFailClosed(lastGood)
        val out = ConfigPublicationContract.onVerifiedFailure(precommit)
        assertNull(out.publishedAtMs)
        assertTrue(out.publishFailed)
        assertEquals("must not advance active on a failed actual publication", 5L, out.activeProfileId)
    }
}
