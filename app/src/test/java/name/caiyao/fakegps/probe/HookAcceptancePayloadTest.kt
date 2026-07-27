package name.caiyao.fakegps.probe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HookAcceptancePayloadTest {

    @Test
    fun acceptsCanonicalSchemaV2EnvelopeWithoutLosingLongValues() {
        val raw = """
            {
              "schemaVersion": 2,
              "mode": "always_on",
              "fields": {
                "mcc": 310,
                "mnc": 260,
                "nci": 68719400000,
                "operator_name": "HOOK-LAB",
                "is_roaming": 0
              }
            }
        """.trimIndent()

        val validated = HookAcceptancePayload.validate("acceptance-123", raw)
        val root = Json.parseToJsonElement(validated.json).jsonObject
        val fields = root.getValue("fields").jsonObject

        assertEquals("acceptance-123", validated.sessionId)
        assertEquals(2, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("always_on", root.getValue("mode").jsonPrimitive.content)
        assertEquals(68_719_400_000L, fields.getValue("nci").jsonPrimitive.content.toLong())
        assertEquals("HOOK-LAB", fields.getValue("operator_name").jsonPrimitive.content)
        assertEquals(0, fields.getValue("is_roaming").jsonPrimitive.content.toInt())
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":1,"mode":"always_on","fields":{"tac":4095}}""",
            )
        }

        assertEquals("schemaVersion must be 2", failure.message)
    }

    @Test
    fun rejectsBlankOrUnsafeSessionIds() {
        val raw = """{"schemaVersion":2,"mode":"always_on","fields":{"tac":4095}}"""

        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate("", raw)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate("acceptance id with spaces", raw)
        }
    }

    @Test
    fun rejectsNonObjectFieldsAndNonScalarValues() {
        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":2,"mode":"always_on","fields":[]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":2,"mode":"always_on","fields":{"tac":{"value":4095}}}""",
            )
        }
    }

    @Test
    fun rejectsFieldsOutsideTheCellularProfileContract() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            HookAcceptancePayload.validate(
                "acceptance-123",
                """{"schemaVersion":2,"mode":"always_on","fields":{"wifi_ssid":"not-cellular"}}""",
            )
        }

        assertEquals("unsupported acceptance fields: wifi_ssid", failure.message)
    }

    @Test
    fun acceptsCellularControlsAndNeighborJsonAsScalarFields() {
        val validated = HookAcceptancePayload.validate(
            "acceptance-neighbors",
            """
                {
                  "schemaVersion": 2,
                  "mode": "always_on",
                  "fields": {
                    "signal_fluctuation_enabled": 0,
                    "signal_fluctuation_range_db": 6,
                    "neighbor_cells_json": "[{\"type\":\"gsm\",\"cid\":2222}]"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            """{"schemaVersion":2,"mode":"always_on","fields":{"signal_fluctuation_enabled":0,"signal_fluctuation_range_db":6,"neighbor_cells_json":"[{\"type\":\"gsm\",\"cid\":2222}]"}}""",
            validated.json,
        )
    }
}
