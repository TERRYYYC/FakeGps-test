package name.caiyao.fakegps.verify

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the verify screen's reconciliation logic.
 *
 * The screen answers ONE question per field — "did the value I configured actually reach the app?"
 * — by joining two independently-sourced maps on the profile table's column name:
 *   configured := the published transport payload (what the hook reads), NOT the DB row
 *   observed   := what this process reads back through the same public Android APIs a target app uses
 *
 * Joining on dbColumn keeps this generic: no per-field comparison code, so a new column can never
 * silently drop out of verification the way it did from the transport (23 of 87 columns carried).
 */
class VerificationEngineTest {

    private val lteSpecs = linkedMapOf(
        "小区标识 - LTE" to listOf(
            FieldSpec("tac", "TAC", "", FieldType.INTEGER),
            FieldSpec("ci", "CI", "", FieldType.INTEGER),
            FieldSpec("pci", "PCI", "", FieldType.INTEGER),
        ),
    )

    // ---- per-field verdicts -----------------------------------------------------------------

    @Test
    fun `configured value observed back verbatim is SPOOFED`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "26999"),
            observed = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("tac").verdict)
    }

    @Test
    fun `configured value observed as something else is MISMATCH`() {
        // The exact shape of the original bug: profile said ci=99999, the app still saw the real cell.
        val r = VerificationEngine.buildReport(
            configured = mapOf("ci" to "99999"),
            observed = mapOf("ci" to "28378431"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("ci").verdict)
    }

    @Test
    fun `configured but the API returned nothing is UNOBSERVABLE not MISMATCH`() {
        // getAllCellInfo() returns an empty list on some devices/ROMs. That is not evidence the
        // hook failed, so it must not be reported as a failure.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "26999"),
            observed = emptyMap(),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.UNOBSERVABLE, r.field("tac").verdict)
    }

    @Test
    fun `unconfigured field showing a real value is PASSTHROUGH not a failure`() {
        // NULL = passthrough is the project's core invariant; seeing the real value here is correct.
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            observed = mapOf("pci" to "53"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.PASSTHROUGH, r.field("pci").verdict)
    }

    @Test
    fun `field that is neither configured nor observed is omitted entirely`() {
        // Listing 87 rows of "null / null" would bury the handful of rows that carry information.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("tac" to "1"),
            specs = lteSpecs,
        )
        assertEquals(listOf("tac"), r.allFields().map { it.spec.dbColumn })
    }

    // ---- value normalisation ----------------------------------------------------------------

    @Test
    fun `integer column compares equal across transport and API string forms`() {
        // mcc is an INTEGER column; CellIdentity exposes it as mccString. Same value, two shapes.
        val specs = linkedMapOf("x" to listOf(FieldSpec("mcc", "MCC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("mcc" to "460"),
            observed = mapOf("mcc" to "460"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("mcc").verdict)
    }

    @Test
    fun `numerically equal values with different padding still match`() {
        // SQLite stores mnc as INTEGER 0; the radio reports mncString "00".
        val specs = linkedMapOf("x" to listOf(FieldSpec("mnc", "MNC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("mnc" to "0"),
            observed = mapOf("mnc" to "00"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("mnc").verdict)
    }

    @Test
    fun `boolean column matches across numeric and literal forms`() {
        // is_roaming is stored 0/1 but TelephonyManager#isNetworkRoaming returns false/true.
        val specs = linkedMapOf("x" to listOf(FieldSpec("is_roaming", "漫游", "", FieldType.BOOLEAN)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("is_roaming" to "1"),
            observed = mapOf("is_roaming" to "true"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("is_roaming").verdict)
    }

    @Test
    fun `text column is compared verbatim because the hook returns it unchanged`() {
        // The hook hands back the configured string as-is, so any difference means the value did
        // NOT come from us — case included.
        val specs = linkedMapOf("x" to listOf(FieldSpec("operator_name", "运营商", "", FieldType.TEXT)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("operator_name" to "CMCC"),
            observed = mapOf("operator_name" to "cmcc"),
            specs = specs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("operator_name").verdict)
    }

    @Test
    fun `surrounding whitespace does not create a false mismatch`() {
        val specs = linkedMapOf("x" to listOf(FieldSpec("operator_name", "运营商", "", FieldType.TEXT)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("operator_name" to "CMCC "),
            observed = mapOf("operator_name" to "CMCC"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("operator_name").verdict)
    }

    // ---- summary ----------------------------------------------------------------------------

    @Test
    fun `summary counts each verdict so the header can state the outcome in one line`() {
        val specs = linkedMapOf(
            "c" to listOf(
                FieldSpec("tac", "TAC", "", FieldType.INTEGER),
                FieldSpec("ci", "CI", "", FieldType.INTEGER),
                FieldSpec("pci", "PCI", "", FieldType.INTEGER),
                FieldSpec("earfcn", "EARFCN", "", FieldType.INTEGER),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2", "pci" to "3"),
            observed = mapOf("tac" to "1", "ci" to "999", "earfcn" to "1850"),
            specs = specs,
        )
        assertEquals(1, r.summary.spoofed)       // tac
        assertEquals(1, r.summary.mismatch)      // ci
        assertEquals(1, r.summary.unobservable)  // pci
        assertEquals(1, r.summary.passthrough)   // earfcn
    }

    @Test
    fun `overall status is FAILING when any configured field did not take effect`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = mapOf("tac" to "1", "ci" to "999"),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.FAILING, r.summary.status)
    }

    @Test
    fun `overall status is EFFECTIVE when every configured field was observed back`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = mapOf("tac" to "1", "ci" to "2"),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.EFFECTIVE, r.summary.status)
    }

    @Test
    fun `overall status is NOTHING_CONFIGURED when the profile spoofs nothing`() {
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            observed = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.NOTHING_CONFIGURED, r.summary.status)
    }

    @Test
    fun `overall status is INCONCLUSIVE when configured fields could not be observed at all`() {
        // Distinct from FAILING: we have no evidence either way, and saying "failed" would send the
        // user chasing a bug that may not exist.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = emptyMap(),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.INCONCLUSIVE, r.summary.status)
    }

    // ---- transport drift visibility ---------------------------------------------------------

    @Test
    fun `payload columns absent from the field spec are counted so transport drift stays visible`() {
        // A column that reaches the hook but has no UI row would otherwise be invisible — exactly
        // how 64 of 87 columns went missing unnoticed.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "some_new_column" to "7"),
            observed = mapOf("tac" to "1"),
            specs = lteSpecs,
        )
        assertEquals(2, r.payloadFieldCount)
        assertEquals(listOf("some_new_column"), r.unmappedPayloadColumns)
    }

    @Test
    fun `report preserves the field spec category order for a stable screen layout`() {
        val specs = linkedMapOf(
            "小区标识 - LTE" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
            "信号 - LTE" to listOf(FieldSpec("lte_rsrp", "RSRP", "", FieldType.INTEGER)),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("lte_rsrp" to "-95"),
            specs = specs,
        )
        assertEquals(listOf("小区标识 - LTE", "信号 - LTE"), r.groups.map { it.category })
    }

    @Test
    fun `categories with nothing to report are dropped`() {
        val specs = linkedMapOf(
            "小区标识 - LTE" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
            "WiFi" to listOf(FieldSpec("wifi_ssid", "SSID", "", FieldType.TEXT)),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("tac" to "1"),
            specs = specs,
        )
        assertEquals(listOf("小区标识 - LTE"), r.groups.map { it.category })
    }

    // ---- observation scope: this process is not always hooked --------------------------------

    @Test
    fun `configured field seen only as a real baseline is UNOBSERVABLE not MISMATCH`() {
        // A release build deliberately does NOT hook its own process (MainHook), so this screen
        // reads the REAL value by design. Calling that a MISMATCH would tell the user the hook is
        // broken when it may be working perfectly inside the target app. "No evidence" is the only
        // honest verdict, with the real value carried alongside for reference.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = emptyMap(),
            baseline = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.UNOBSERVABLE, r.field("tac").verdict)
        assertEquals("26999", r.field("tac").baseline)
        assertEquals(VerificationStatus.INCONCLUSIVE, r.summary.status)
    }

    @Test
    fun `field known only from the baseline is reported so the user can see the real network`() {
        // This is what makes "pick a value different from the real network" actionable.
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            observed = emptyMap(),
            baseline = mapOf("pci" to "53"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.PASSTHROUGH, r.field("pci").verdict)
        assertEquals("53", r.field("pci").baseline)
    }

    // ---- ambiguity: configured value identical to the real one ------------------------------

    @Test
    fun `field configured to a value equal to the real baseline is flagged ambiguous`() {
        // If the configured value matches what the device would report anyway, "observed == configured"
        // proves nothing. The user must be told to pick a distinguishing value.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "26999"),
            observed = mapOf("tac" to "26999"),
            baseline = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertTrue(r.field("tac").ambiguous)
    }

    @Test
    fun `field configured to a value differing from the real baseline is unambiguous`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = mapOf("tac" to "12345"),
            baseline = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertTrue(!r.field("tac").ambiguous)
    }
}

private fun VerificationReport.allFields() = groups.flatMap { it.fields }
private fun VerificationReport.field(col: String) =
    allFields().first { it.spec.dbColumn == col }
