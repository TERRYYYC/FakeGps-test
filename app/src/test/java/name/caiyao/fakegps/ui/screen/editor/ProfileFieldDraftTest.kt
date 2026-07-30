package name.caiyao.fakegps.ui.screen.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFieldDraftTest {

    @Test
    fun `blank unavailable and concrete values are mutually exclusive`() {
        var draft = ProfileFieldDraft.update(emptyMap(), "tac", "--")
        assertEquals("--", draft["tac"])

        draft = ProfileFieldDraft.update(draft, "tac", "4095")
        assertEquals("4095", draft["tac"])

        draft = ProfileFieldDraft.update(draft, "tac", "")
        assertFalse(draft.containsKey("tac"))
    }

    @Test
    fun `unsupported field cannot be marked unavailable`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileFieldDraft.update(emptyMap(), "is_roaming", "--")
        }
    }

    @Test
    fun `split never leaks display token into typed values`() {
        val split = ProfileFieldDraft.split(mapOf(
            "tac" to "--",
            "operator_name" to "Test Carrier",
        ))

        assertEquals(setOf("tac"), split.unavailable)
        assertEquals(mapOf("operator_name" to "Test Carrier"), split.values)
        assertFalse(split.values.values.contains("--"))
    }

    @Test
    fun `loading overlays unavailable state on concrete map`() {
        val shown = ProfileFieldDraft.forDisplay(
            values = mapOf("operator_name" to "Test Carrier"),
            unavailable = setOf("tac"),
        )
        assertEquals("--", shown["tac"])
        assertEquals("Test Carrier", shown["operator_name"])
        assertTrue(shown.size == 2)
    }
}
