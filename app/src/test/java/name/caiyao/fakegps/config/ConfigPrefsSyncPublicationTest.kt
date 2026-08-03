package name.caiyao.fakegps.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPrefsSyncPublicationTest {

    @Test
    fun transientMissingSelectedProfileKeepsTheLastGoodPayload() {
        assertTrue(
            ConfigPublicationContract.shouldKeepLastGoodPayload(
                requestedProfileId = 7L,
                resolvedProfileId = null,
                clearIfMissing = false,
            ),
        )
    }

    @Test
    fun explicitDeleteMayPublishAnEmptyPayload() {
        assertFalse(
            ConfigPublicationContract.shouldKeepLastGoodPayload(
                requestedProfileId = 7L,
                resolvedProfileId = null,
                clearIfMissing = true,
            ),
        )
    }

    @Test
    fun freshInstallWithoutASelectedProfileMayPublishEmpty() {
        assertFalse(
            ConfigPublicationContract.shouldKeepLastGoodPayload(
                requestedProfileId = null,
                resolvedProfileId = null,
                clearIfMissing = false,
            ),
        )
    }

    @Test
    fun privateFallbackCommitDoesNotCountAsCrossProcessPublication() {
        assertFalse(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(
                worldReadable = false,
                committed = true,
            ),
        )
    }

    @Test
    fun worldReadableCommitCountsAsCrossProcessPublication() {
        assertTrue(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(
                worldReadable = true,
                committed = true,
            ),
        )
    }

    @Test
    fun failedWorldReadableCommitDoesNotCountAsPublication() {
        assertFalse(
            ConfigPublicationContract.isCrossProcessPublishSuccessful(
                worldReadable = true,
                committed = false,
            ),
        )
    }
}
