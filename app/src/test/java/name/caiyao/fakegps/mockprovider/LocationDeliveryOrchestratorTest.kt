package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDeliveryOrchestratorTest {

    @Test
    fun `enable starts provider before publishing system mock intent`() {
        val fixture = Fixture()

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Running)
        assertEquals(
            listOf(
                "cleanup:true",
                "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "cleanup:false",
            ),
            fixture.events,
        )
        assertFalse(fixture.cleanupRequired)
    }

    @Test
    fun `config publication failure rolls intent back to hook and removes provider`() {
        val fixture = Fixture(publishResults = ArrayDeque(listOf(false, true)))

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish",
                "mode:hook", "config:publish", "provider:remove",
                "cleanup:false",
            ),
            fixture.events,
        )
    }

    @Test
    fun `rollback persistence failure leaves transaction marker for safe startup cleanup`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.HOOK,
            publishResults = ArrayDeque(listOf(false)),
            persistModeResults = ArrayDeque(listOf(true, false)),
        )

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(LocationDeliveryMode.SYSTEM_MOCK, fixture.mode)
        assertTrue(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "mode:hook", "provider:remove",
            ),
            fixture.events,
        )
    }

    @Test
    fun `enable never mutates system state when durable cleanup marker cannot be saved`() {
        val fixture = Fixture(cleanupResults = ArrayDeque(listOf(false)))

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(listOf("cleanup:true"), fixture.events)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
    }

    @Test
    fun `provider start failure leaves durable cleanup marker for next launch`() {
        val fixture = Fixture(providerFailure = "replace")

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertTrue(fixture.cleanupRequired)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertEquals(
            listOf(
                "cleanup:true",
                "provider:remove", "provider:replace", "provider:remove",
            ),
            fixture.events,
        )
    }

    @Test
    fun `disable persists hook intent then cleans orphan even from fresh controller`() {
        val fixture = Fixture(initialMode = LocationDeliveryMode.SYSTEM_MOCK)

        val result = fixture.orchestrator.disable()

        assertEquals(MockProviderState.Idle, result)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `invalid profile while system mock is selected rolls back intent and provider`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `invalid profile in ordinary hook mode does not touch system provider`() {
        val fixture = Fixture(
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `disable still removes provider when hook publication fails`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            publishResults = ArrayDeque(listOf(false, true)),
        )

        val result = fixture.orchestrator.disable()

        assertTrue(result is MockProviderState.Failed)
        assertTrue(fixture.cleanupRequired)
        assertTrue(fixture.events.contains("provider:remove"))

        fixture.events.clear()
        val retried = fixture.orchestrator.disable()

        assertEquals(MockProviderState.Idle, retried)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `refresh publishes same profile and replaces provider when effective profile changes`() {
        val kyiv = published(50.4501, 30.5234)
        val lviv = published(49.8397, 24.0297)
        val profiles = ArrayDeque(listOf(kyiv, kyiv, lviv))
        val fixture = Fixture(readPublished = { profiles.removeFirst() })

        fixture.orchestrator.enable()
        fixture.events.clear()
        fixture.orchestrator.refresh()
        fixture.orchestrator.refresh()

        assertEquals(
            listOf(
                "provider:publish",
                "provider:remove", "provider:replace", "provider:publish",
            ),
            fixture.events,
        )
    }

    private class Fixture(
        initialMode: LocationDeliveryMode = LocationDeliveryMode.HOOK,
        private val publishResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        private val persistModeResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        private val cleanupResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        private val providerFailure: String? = null,
        readPublished: () -> PublishedConfig? = { published(50.4501, 30.5234) },
    ) {
        val events = mutableListOf<String>()
        var mode = initialMode
        var cleanupRequired = false
        private val gateway = object : MockProviderGateway {
            override fun replaceGpsProvider() {
                events += "provider:replace"
                if (providerFailure == "replace") error("replace failed")
            }
            override fun publish(config: MockLocationConfig) {
                events += "provider:publish"
                if (providerFailure == "publish") error("publish failed")
            }
            override fun removeGpsProvider() {
                events += "provider:remove"
                if (providerFailure == "remove") error("remove failed")
            }
        }
        val orchestrator = LocationDeliveryOrchestrator(
            controller = MockProviderSessionController(gateway),
            readPublished = readPublished,
            readMode = { mode },
            readCleanupRequired = { cleanupRequired },
            persistMode = {
                events += "mode:${it.wireValue}"
                val result = persistModeResults.removeFirstOrNull() ?: true
                if (result) mode = it
                result
            },
            publishConfig = {
                events += "config:publish"
                publishResults.removeFirstOrNull() ?: true
            },
            persistCleanupRequired = {
                events += "cleanup:$it"
                val result = cleanupResults.removeFirstOrNull() ?: true
                if (result) cleanupRequired = it
                result
            },
        )
    }

    companion object {
        private fun published(latitude: Double, longitude: Double) = PublishedConfig(
            schemaVersion = 4,
            mode = "always_on",
            fields = mapOf(
                "latitude" to latitude.toString(),
                "longitude" to longitude.toString(),
            ),
            locationDeliveryMode = "hook",
        )
    }
}
