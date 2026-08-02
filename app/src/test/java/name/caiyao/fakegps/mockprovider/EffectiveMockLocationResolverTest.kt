package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveMockLocationResolverTest {

    @Test
    fun `resolves Kyiv from the same published effective profile the hook consumes`() {
        val result = EffectiveMockLocationResolver.resolve(
            config(
                fields = mapOf(
                    "latitude" to "50.4501",
                    "longitude" to "30.5234",
                    "accuracy" to "4.5",
                    "tac" to "27101",
                ),
            ),
        )

        assertEquals(
            EffectiveMockLocationResolution.Ready(
                MockLocationConfig(50.4501, 30.5234, 4.5f),
            ),
            result,
        )
    }

    @Test
    fun `missing accuracy uses explicit product default`() {
        val result = EffectiveMockLocationResolver.resolve(
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234")),
        )

        assertEquals(
            EffectiveMockLocationResolution.Ready(MockLocationConfig(50.4501, 30.5234, 3f)),
            result,
        )
    }

    @Test
    fun `missing incomplete nonnumeric and out of range coordinates are rejected`() {
        val cases = listOf(
            null,
            config(fields = emptyMap()),
            config(fields = mapOf("latitude" to "50.4501")),
            config(fields = mapOf("latitude" to "Kyiv", "longitude" to "30.5234")),
            config(fields = mapOf("latitude" to "91", "longitude" to "30.5234")),
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "181")),
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234", "accuracy" to "0")),
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234", "accuracy" to "unknown")),
        )

        cases.forEach { published ->
            assertTrue(
                "expected rejection for $published",
                EffectiveMockLocationResolver.resolve(published) is EffectiveMockLocationResolution.Invalid,
            )
        }
    }

    @Test
    fun `structurally incomplete or unsupported payload is rejected`() {
        val noFields = config(fields = emptyMap()).copy(fieldsPresent = false)
        val future = config(fields = mapOf("latitude" to "50", "longitude" to "30"))
            .copy(schemaVersion = 99)

        assertTrue(EffectiveMockLocationResolver.resolve(noFields) is EffectiveMockLocationResolution.Invalid)
        assertTrue(EffectiveMockLocationResolver.resolve(future) is EffectiveMockLocationResolution.Invalid)
    }

    private fun config(fields: Map<String, String>) = PublishedConfig(
        schemaVersion = 4,
        mode = "always_on",
        fields = fields,
        locationDeliveryMode = "hook",
    )
}
