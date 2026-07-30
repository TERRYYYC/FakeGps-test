package name.caiyao.fakegps.config

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import name.caiyao.fakegps.verify.VerificationEngine
import name.caiyao.fakegps.verify.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between two contracts that were developed in separate PRs.
 *
 *  - PR #4 owns publication success: [ConfigPublicationContract] — true only when the write is
 *    cross-process readable AND committed. Its durable-restore transaction depends on
 *    "false == cross-process publication failed" being a fact.
 *  - PR #3 owns the propagation window: a mismatch seen within ~30s of a publish is staleness,
 *    not failure, because the hook re-reads on a timer.
 *
 * Composed, they must satisfy: **a failed publication can never soften a mismatch into "pending"**.
 * Otherwise a permanently broken transport (MODE_PRIVATE fallback — commits fine, unreadable from
 * another process) would render as "配置刚保存，稍等一下", forever, instead of a visible failure.
 *
 * Neither PR's own tests cover this: #4 cannot see the UI status, #3 cannot see the write path.
 * Locked here so a future edit to either side cannot quietly resurrect the false red.
 */
class PublicationPendingSeamTest {

    private val specs = linkedMapOf(
        "c" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
    )

    /** Mirrors ConfigPrefsSync#sync: the timestamp is written only when publication succeeded. */
    private fun timestampAfterSync(worldReadable: Boolean, committed: Boolean, nowMs: Long): Long? =
        if (ConfigPublicationContract.isCrossProcessPublishSuccessful(worldReadable, committed)) {
            nowMs
        } else {
            null
        }

    private fun statusFor(publishedAtMs: Long?, nowMs: Long): VerificationStatus =
        VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = mapOf("tac" to "26999"),   // the configured value did NOT take effect
            propagationPending = PublishPropagation.isPending(publishedAtMs, nowMs),
            specs = specs,
        ).summary.status

    @Test
    fun `world-readable fallback rejected means a mismatch is a real failure, never pending`() {
        // MODE_WORLD_READABLE refused -> MODE_PRIVATE. commit() still returns true, but no other
        // process can ever read the file: the spoof will never work, and the user must see that.
        val ts = timestampAfterSync(worldReadable = false, committed = true, nowMs = 1_000)
        assertFalse("a failed publication must not record a propagation timestamp", ts != null)
        assertEquals(VerificationStatus.FAILING, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `a failed commit likewise cannot mask the mismatch`() {
        val ts = timestampAfterSync(worldReadable = true, committed = false, nowMs = 1_000)
        assertEquals(VerificationStatus.FAILING, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `a successful publish does soften the mismatch inside the window`() {
        // The legitimate case this whole mechanism exists for: saved seconds ago, hook has not
        // re-read yet, so "未生效" would be a lie.
        val ts = timestampAfterSync(worldReadable = true, committed = true, nowMs = 1_000)
        assertTrue(ts != null)
        assertEquals(VerificationStatus.PENDING_PROPAGATION, statusFor(ts, nowMs = 1_000))
    }

    @Test
    fun `a successful publish stops softening once the window elapses`() {
        val ts = timestampAfterSync(worldReadable = true, committed = true, nowMs = 1_000)
        val afterWindow = 1_000L + PublishPropagation.HOOK_REFRESH_INTERVAL_MS
        assertEquals(VerificationStatus.FAILING, statusFor(ts, afterWindow))
    }

    @Test
    fun `publication contract is exactly world-readable AND committed`() {
        // Guards the assumption the seam is built on. If #4 ever widens this, the timestamp gate in
        // ConfigPrefsSync#sync must be revisited in the same change.
        assertTrue(ConfigPublicationContract.isCrossProcessPublishSuccessful(true, true))
        assertFalse(ConfigPublicationContract.isCrossProcessPublishSuccessful(false, true))
        assertFalse(ConfigPublicationContract.isCrossProcessPublishSuccessful(true, false))
        assertFalse(ConfigPublicationContract.isCrossProcessPublishSuccessful(false, false))
    }
}
