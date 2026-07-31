package name.caiyao.fakegps.ui.screen.verify

import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.verify.VerificationSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyUiContractTest {

    @Test
    fun payloadCompatibilityUsesTheSharedTransportContract() {
        assertTrue(PayloadStatus.Ok(ConfigPrefsSync.SCHEMA_VERSION, 1).compatible)
        assertTrue(PayloadStatus.Ok(ConfigPrefsSync.LEGACY_SCHEMA_VERSION, 1).compatible)
        assertFalse(PayloadStatus.Ok(1, 1).compatible)
    }

    @Test
    fun partialCopyExplainsAmbiguousFieldsInsteadOfRenderingAnEmptyClause() {
        val detail = partialVerificationDetail(
            VerificationSummary(
                spoofed = 1,
                mismatch = 0,
                unobservable = 0,
                passthrough = 0,
                ambiguous = 2,
            ),
        )

        assertTrue(detail.contains("2 个值与真实值相同"))
        assertTrue(detail.contains("明显不同"))
        assertFalse(detail.contains("另有 ，"))
    }
}
