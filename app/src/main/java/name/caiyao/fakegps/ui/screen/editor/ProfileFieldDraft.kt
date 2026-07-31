package name.caiyao.fakegps.ui.screen.editor

import name.caiyao.fakegps.config.UnavailablePayloadContract
import name.caiyao.fakegps.config.UnavailableSpec
import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType

/** Pure three-state editor reducer: blank = passthrough, "--" = unavailable, value = spoof. */
object ProfileFieldDraft {
    const val UNAVAILABLE_TOKEN = "--"

    data class Split(
        val values: Map<String, String>,
        val unavailable: Set<String>,
    )

    fun update(current: Map<String, String>, column: String, input: String): Map<String, String> {
        val next = current.toMutableMap()
        val normalized = if (input.trim() == UNAVAILABLE_TOKEN) UNAVAILABLE_TOKEN else input
        when {
            normalized.isBlank() -> {
                next.remove(column)
                if (column in PLMN_FIELDS && current[column] == UNAVAILABLE_TOKEN) {
                    next.remove(otherPlmn(column))
                }
            }
            normalized == UNAVAILABLE_TOKEN -> {
                next[column] = UNAVAILABLE_TOKEN
                if (column in PLMN_FIELDS && UnavailableSpec.supportsUnavailable(column)) {
                    next[otherPlmn(column)] = UNAVAILABLE_TOKEN
                }
            }
            else -> {
                next[column] = normalized
                if (column in PLMN_FIELDS && current[column] == UNAVAILABLE_TOKEN) {
                    next.remove(otherPlmn(column))
                }
            }
        }
        return next
    }

    fun validationErrors(draft: Map<String, String>): Map<String, String> {
        val specs = FieldSpec.allCategories().values.flatten().associateBy { it.dbColumn }
        return buildMap {
            for ((column, raw) in draft) {
                val spec = specs[column] ?: run {
                    put(column, "未知字段")
                    continue
                }
                if (raw == UNAVAILABLE_TOKEN) {
                    if (!UnavailableSpec.supportsUnavailable(column)) {
                        put(column, "此字段不支持不上报；请留空以透传真实值")
                    }
                    continue
                }
                val valid = when (spec.type) {
                    FieldType.TEXT -> true
                    FieldType.INTEGER, FieldType.BOOLEAN -> raw.toIntOrNull() != null
                    FieldType.DOUBLE -> raw.toDoubleOrNull() != null
                    FieldType.FLOAT -> raw.toFloatOrNull() != null
                }
                if (!valid) put(column, "${spec.displayName}格式无效，不能保存为透传")
            }
        }
    }

    fun requireValid(draft: Map<String, String>) {
        val errors = validationErrors(draft)
        require(errors.isEmpty()) { errors.entries.joinToString { "${it.key}: ${it.value}" } }
    }

    fun split(draft: Map<String, String>): Split {
        requireValid(draft)
        val unavailable = draft.filterValues { it == UNAVAILABLE_TOKEN }.keys.toList()
        val values = draft.filterValues { it != UNAVAILABLE_TOKEN }
        val validated = UnavailablePayloadContract.validate(values.keys, unavailable)
        return Split(values, validated.asSet())
    }

    fun forDisplay(values: Map<String, String>, unavailable: Set<String>): Map<String, String> {
        val validated = UnavailablePayloadContract.validate(values.keys, unavailable.toList())
        return values + validated.asSet().associateWith { UNAVAILABLE_TOKEN }
    }

    private val PLMN_FIELDS = setOf("mcc", "mnc")

    private fun otherPlmn(column: String): String = if (column == "mcc") "mnc" else "mcc"
}
