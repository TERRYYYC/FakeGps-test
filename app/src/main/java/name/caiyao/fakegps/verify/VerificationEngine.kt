package name.caiyao.fakegps.verify

import name.caiyao.fakegps.data.model.FieldSpec

/**
 * Outcome for a single field: did the value the user configured actually reach the app?
 *
 * [PASSTHROUGH] and [UNOBSERVABLE] are deliberately NOT failures. Reporting either as one would
 * train the user to ignore the screen — passthrough is the project's core invariant working as
 * designed, and an unreadable API is a device limitation, not evidence about the hook.
 */
enum class FieldVerdict {
    /** Configured, and the app reads back exactly that value. The hook works for this field. */
    SPOOFED,

    /** Configured, but the app reads back something else — the chain is broken for this field. */
    MISMATCH,

    /** Configured, but this process cannot read the field at all. No evidence either way. */
    UNOBSERVABLE,

    /**
     * Configured, but NO Android getter exists for it in any app — a module control knob such as
     * signal_fluctuation_*. Distinct from [UNOBSERVABLE], which is a device/API limitation the user
     * could work around on other hardware; this one is unverifiable everywhere, by design.
     *
     * Kept separate so the row chip and the summary counter agree. Reusing UNOBSERVABLE made rows
     * render "读不到" while the summary printed "读不到 0".
     */
    NOT_VERIFIABLE,

    /** Not configured, so the app correctly sees the real device value. */
    PASSTHROUGH,
}

/** Screen-level roll-up of [FieldVerdict]s. */
enum class VerificationStatus {
    /** Every configured field was observed back. */
    EFFECTIVE,

    /** Some fields confirmed, others unreadable or unverifiable — real evidence, but incomplete. */
    PARTIALLY_EFFECTIVE,

    /**
     * Only fields that no getter can report were configured (module control knobs). Nothing could
     * be checked, but the profile is definitely not empty.
     */
    CONFIGURED_UNVERIFIABLE,

    /** At least one configured field was observed with a different value. */
    FAILING,

    /**
     * Fields mismatch, but the config was published so recently that the hook may not have re-read
     * it yet. Reporting failure here would blame the hook for being one refresh tick behind —
     * right after saving, which is exactly when users check.
     */
    PENDING_PROPAGATION,

    /** Fields are configured but none could be observed — cannot conclude anything. */
    INCONCLUSIVE,

    /** The effective profile configures nothing, so there is nothing to verify. */
    NOTHING_CONFIGURED,
}

data class FieldReport(
    val spec: FieldSpec,
    /** Value carried by the published transport payload, i.e. what the hook actually receives. */
    val configured: String?,
    /** Value this process reads back through the same public API a target app would call. */
    val observed: String?,
    /**
     * The device's REAL value, when it is knowable. Populated instead of [observed] whenever this
     * process is not itself hooked, so the screen can still show the user what the real network
     * looks like — which is what makes "choose a value that differs from it" actionable.
     */
    val baseline: String? = null,
    val verdict: FieldVerdict,
    /**
     * True when the configured value equals the device's real value, so the field can never prove
     * anything: "observed == configured" is indistinguishable from the hook doing nothing.
     *
     * Only computable where a real baseline is knowable, i.e. when this process is NOT self-hooked.
     * A self-hooked process cannot see past its own hook, so ambiguity is undetectable there — the
     * scope banner states that limitation rather than pretending the check ran.
     */
    val ambiguous: Boolean = false,
)

data class CategoryReport(
    val category: String,
    val fields: List<FieldReport>,
)

data class VerificationSummary(
    val spoofed: Int,
    val mismatch: Int,
    val unobservable: Int,
    val passthrough: Int,
    /**
     * Configured fields that no Android getter can ever report (module control knobs such as
     * signal_fluctuation_*). Distinct from [unobservable], which is a device/API limitation: these
     * are unverifiable BY DESIGN, so they are not evidence — but they ARE configured, and the hook
     * does apply them.
     */
    val notVerifiable: Int = 0,
    /** True while the hook may still be serving the previous config. See [VerificationStatus.PENDING_PROPAGATION]. */
    val propagationPending: Boolean = false,
) {
    /** Everything the user actually configured, verifiable or not. */
    val configuredCount: Int get() = spoofed + mismatch + unobservable + notVerifiable

    val status: VerificationStatus
        get() = when {
            configuredCount == 0 -> VerificationStatus.NOTHING_CONFIGURED
            // A mismatch inside the propagation window is more likely staleness than failure, so
            // it must not be announced as one.
            mismatch > 0 && propagationPending -> VerificationStatus.PENDING_PROPAGATION
            mismatch > 0 -> VerificationStatus.FAILING
            // Only module knobs configured: nothing was checkable, but saying "没有配置任何字段"
            // would contradict both the payload and the hook.
            spoofed == 0 && unobservable == 0 -> VerificationStatus.CONFIGURED_UNVERIFIABLE
            spoofed == 0 -> VerificationStatus.INCONCLUSIVE
            // Confirming one field out of eleven is not "伪装生效". Announcing full success while
            // part of the config is unverified is the same overclaim this screen exists to remove.
            unobservable > 0 || notVerifiable > 0 -> VerificationStatus.PARTIALLY_EFFECTIVE
            else -> VerificationStatus.EFFECTIVE
        }
}

data class VerificationReport(
    val groups: List<CategoryReport>,
    val summary: VerificationSummary,
    /** Number of fields present in the published payload — the hook's actual input size. */
    val payloadFieldCount: Int,
    /**
     * Payload columns with no [FieldSpec] row. These reach the hook but have no UI, so they can
     * neither be configured nor verified; surfacing the count keeps that drift visible instead of
     * letting it rot silently the way 64 of 87 columns once did.
     */
    val unmappedPayloadColumns: List<String>,
)

/**
 * Reconciles what was configured against what the app can actually observe.
 *
 * The two sides are sourced independently and joined on the profile table's column name:
 *
 *   configured — read back from the WORLD-READABLE payload the hook consumes, NOT from the DB row.
 *                Reading the DB would verify only that the UI saved something; reading the payload
 *                also proves the transport carried it, which is precisely where fields were lost
 *                before (23 of 87 columns).
 *   observed   — read through the same public Android APIs a target app calls.
 *
 * Joining on [FieldSpec.dbColumn] means there is no per-field comparison code: a new column is
 * covered the moment it appears in the spec, so verification cannot drift out of sync with the
 * field model the way hand-maintained mappings did.
 */
object VerificationEngine {

    /**
     * Columns that reach the hook but that no Android getter can ever report back.
     *
     * `signal_fluctuation_*` are module control knobs, and `addname` is a display label regenerated
     * on every save. Filing them under 读不到 would drag an otherwise-conclusive screen to
     * INCONCLUSIVE and keep a transport-drift alarm permanently lit — and an alarm that is always on
     * is one nobody reads.
     */
    private val NOT_DEVICE_OBSERVABLE = setOf(
        "signal_fluctuation_enabled",
        "signal_fluctuation_range_db",
        "neighbor_cells_json",
        "addname",
    )

    fun buildReport(
        configured: Map<String, String>,
        observed: Map<String, String>,
        baseline: Map<String, String> = emptyMap(),
        propagationPending: Boolean = false,
        specs: LinkedHashMap<String, List<FieldSpec>> = FieldSpec.allCategories(),
    ): VerificationReport {
        val groups = mutableListOf<CategoryReport>()
        var spoofed = 0
        var mismatch = 0
        var unobservable = 0
        var notVerifiable = 0
        var passthrough = 0
        val mappedColumns = mutableSetOf<String>()

        for ((category, fields) in specs) {
            val rows = mutableListOf<FieldReport>()
            for (spec in fields) {
                mappedColumns += spec.dbColumn
                val cfg = configured[spec.dbColumn]?.takeIf { it.isNotBlank() }
                val obs = observed[spec.dbColumn]?.takeIf { it.isNotBlank() }
                val real = baseline[spec.dbColumn]?.takeIf { it.isNotBlank() }

                // Nothing configured, nothing read, nothing known: no information to convey.
                // Rendering these would bury the informative rows under ~80 empty ones.
                if (cfg == null && obs == null && real == null) continue

                // A field no getter can report is not evidence either way, so it must not sway the
                // verifiable counters — but if the user configured it, it still counts as
                // configured, or the screen would claim nothing is being spoofed.
                val moduleKnob = spec.dbColumn in NOT_DEVICE_OBSERVABLE
                if (moduleKnob && cfg == null) continue   // nothing configured, nothing to say

                val verdict = when {
                    moduleKnob -> FieldVerdict.NOT_VERIFIABLE
                    cfg == null -> FieldVerdict.PASSTHROUGH
                    obs == null -> FieldVerdict.UNOBSERVABLE
                    valuesMatch(cfg, obs) -> FieldVerdict.SPOOFED
                    else -> FieldVerdict.MISMATCH
                }

                when (verdict) {
                    FieldVerdict.SPOOFED -> spoofed++
                    FieldVerdict.MISMATCH -> mismatch++
                    FieldVerdict.UNOBSERVABLE -> unobservable++
                    FieldVerdict.PASSTHROUGH -> passthrough++
                    FieldVerdict.NOT_VERIFIABLE -> notVerifiable++
                }

                rows += FieldReport(
                    spec = spec,
                    configured = cfg,
                    observed = obs,
                    baseline = real,
                    verdict = verdict,
                    ambiguous = cfg != null && real != null && valuesMatch(cfg, real),
                )
            }
            if (rows.isNotEmpty()) groups += CategoryReport(category, rows)
        }

        return VerificationReport(
            groups = groups,
            summary = VerificationSummary(
                spoofed, mismatch, unobservable, passthrough, notVerifiable, propagationPending,
            ),
            payloadFieldCount = configured.size,
            unmappedPayloadColumns = configured.keys
                .filterNot { it in mappedColumns || it in NOT_DEVICE_OBSERVABLE }
                .sorted(),
        )
    }

    /**
     * Whether an observed value is the configured one.
     *
     * The two sides travel through different type systems — SQLite INTEGER/REAL → JSON number →
     * String on one side, typed Android getters → String on the other — so equal values legitimately
     * arrive in different shapes (`5.0` vs `"5"`, `1` vs `"true"`, `HomeNet` vs `"HomeNet"` with
     * literal quotes). Comparing those verbatim would report a working hook as broken.
     *
     * The tolerance is bounded by what a WORKING hook can actually emit. HookUtils returns
     * `String.valueOf(value)`, which never zero-pads, so an observed `"00"` against a configured
     * `0` cannot have come from the hook — it is the real radio passing through unhooked, and
     * calling it a match would report an entirely inert module as working.
     *
     * Remaining text is compared verbatim, case included: the hook hands back the configured string
     * untouched, so any other difference means the value did not come from us.
     */
    internal fun valuesMatch(configured: String, observed: String): Boolean {
        val a = unquote(configured.trim())
        val b = unquote(observed.trim())
        if (a == b) return true

        asBoolean(a)?.let { ba -> asBoolean(b)?.let { bb -> return ba == bb } }

        // Zero padding is never something the hook produces, so it is signal, not noise.
        if (isZeroPadded(a) || isZeroPadded(b)) return false

        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        if (na != null && nb != null) return na == nb

        return false
    }

    /**
     * `WifiInfo#getSSID` wraps its result in double quotes and the hook reproduces that contract,
     * while the configured column stores plain text. Without this, a correctly spoofed SSID reads
     * as 未生效 on every single refresh.
     */
    private fun unquote(v: String): String =
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v

    /** `"00"`, `"007"` — significant, because `String.valueOf` cannot generate it. `"0.5"` is not. */
    private fun isZeroPadded(v: String): Boolean {
        val s = v.removePrefix("-")
        return s.length > 1 && s[0] == '0' && s[1] != '.'
    }

    /** `1`/`0` (SQLite) and `true`/`false` (Android getters) denote the same boolean. */
    private fun asBoolean(v: String): Boolean? = when (v.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}
