package name.caiyao.fakegps.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPrefsSyncPublicationTest {

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
