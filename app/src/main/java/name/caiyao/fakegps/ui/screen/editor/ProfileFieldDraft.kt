package name.caiyao.fakegps.ui.screen.editor

import name.caiyao.fakegps.config.UnavailablePayloadContract
import name.caiyao.fakegps.config.UnavailableSpec

/** Pure three-state editor reducer: blank = passthrough, "--" = unavailable, value = spoof. */
object ProfileFieldDraft {
    const val UNAVAILABLE_TOKEN = "--"

    data class Split(
        val values: Map<String, String>,
        val unavailable: Set<String>,
    )

    fun update(current: Map<String, String>, column: String, input: String): Map<String, String> {
        val next = current.toMutableMap()
        when {
            input.isBlank() -> next.remove(column)
            input == UNAVAILABLE_TOKEN -> {
                require(UnavailableSpec.supportsUnavailable(column)) {
                    "field does not support unavailable: $column"
                }
                next[column] = UNAVAILABLE_TOKEN
            }
            else -> next[column] = input
        }
        return next
    }

    fun split(draft: Map<String, String>): Split {
        val unavailable = draft.filterValues { it == UNAVAILABLE_TOKEN }.keys.toList()
        val values = draft.filterValues { it != UNAVAILABLE_TOKEN }
        val validated = UnavailablePayloadContract.validate(values.keys, unavailable)
        return Split(values, validated.asSet())
    }

    fun forDisplay(values: Map<String, String>, unavailable: Set<String>): Map<String, String> {
        val validated = UnavailablePayloadContract.validate(values.keys, unavailable.toList())
        return values + validated.asSet().associateWith { UNAVAILABLE_TOKEN }
    }
}
