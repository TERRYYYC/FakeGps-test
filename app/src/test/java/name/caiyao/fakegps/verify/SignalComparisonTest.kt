package name.caiyao.fakegps.verify

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Field-aware comparison: cases where a CORRECTLY working hook does not return the configured value
 * verbatim, so strict equality would report a working module as broken.
 *
 * Each relaxation here is bounded by what the hook can actually emit — deliberately NOT a blanket
 * loosening of numeric comparison, which would resurrect the `mnc=3` vs real `"03"` false success.
 */
class SignalComparisonTest {

    private val lteSignal = linkedMapOf(
        "信号 - LTE" to listOf(FieldSpec("lte_rsrp", "RSRP", "", FieldType.INTEGER, "dBm")),
    )

    // ---- signal fluctuation ------------------------------------------------------------------

    @Test
    fun `with fluctuation off a signal field must still match exactly`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("lte_rsrp" to "-85"),
            observed = mapOf("lte_rsrp" to "-83"),
            specs = lteSignal,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("lte_rsrp").verdict)
    }

    @Test
    fun `with fluctuation on a value inside the window counts as effective`() {
        // Snapshot.fluctuate: base + rnd.nextInt(range+1) - range/2
        // range=6 -> offset in [-3, +3]. Every hookSignal getter goes through this, so requiring
        // exact equality made "enable fluctuation" and "verify" mutually exclusive features.
        val r = VerificationEngine.buildReport(
            configured = mapOf(
                "lte_rsrp" to "-85",
                "signal_fluctuation_enabled" to "1",
                "signal_fluctuation_range_db" to "6",
            ),
            observed = mapOf("lte_rsrp" to "-82"),   // -85 + 3, the upper bound
            specs = lteSignal,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("lte_rsrp").verdict)
    }

    @Test
    fun `with fluctuation on the lower bound is also accepted`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf(
                "lte_rsrp" to "-85",
                "signal_fluctuation_enabled" to "1",
                "signal_fluctuation_range_db" to "6",
            ),
            observed = mapOf("lte_rsrp" to "-88"),   // -85 - 3
            specs = lteSignal,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("lte_rsrp").verdict)
    }

    @Test
    fun `a value outside the fluctuation window is still a mismatch`() {
        // The window must not become a blanket amnesty — a genuinely broken hook has to stay visible.
        val r = VerificationEngine.buildReport(
            configured = mapOf(
                "lte_rsrp" to "-85",
                "signal_fluctuation_enabled" to "1",
                "signal_fluctuation_range_db" to "6",
            ),
            observed = mapOf("lte_rsrp" to "-95"),
            specs = lteSignal,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("lte_rsrp").verdict)
    }

    @Test
    fun `fluctuation does not loosen a non-signal field`() {
        // Cell identity is never routed through hookSignal, so tac must stay exact even with
        // fluctuation enabled.
        val specs = linkedMapOf("x" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf(
                "tac" to "100",
                "signal_fluctuation_enabled" to "1",
                "signal_fluctuation_range_db" to "6",
            ),
            observed = mapOf("tac" to "102"),
            specs = specs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("tac").verdict)
    }

    @Test
    fun `fluctuation enabled with zero range keeps strict equality`() {
        // Snapshot.fluctuate requires range > 0 to perturb anything.
        val r = VerificationEngine.buildReport(
            configured = mapOf(
                "lte_rsrp" to "-85",
                "signal_fluctuation_enabled" to "1",
                "signal_fluctuation_range_db" to "0",
            ),
            observed = mapOf("lte_rsrp" to "-86"),
            specs = lteSignal,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("lte_rsrp").verdict)
    }

    // ---- FLOAT round-trip --------------------------------------------------------------------

    @Test
    fun `FLOAT column survives the double-to-float round trip`() {
        // ProfileEntity.speed is Float; SQLite stores REAL and ConfigPrefsSync reads it back with
        // getDouble, so 0.1f is published as 0.10000000149011612 while Location#getSpeed reports
        // "0.1". Comparing as Double called a correct hook broken.
        val specs = linkedMapOf("定位" to listOf(FieldSpec("speed", "速度", "", FieldType.FLOAT, "m/s")))
        val r = VerificationEngine.buildReport(
            configured = mapOf("speed" to "0.10000000149011612"),
            observed = mapOf("speed" to "0.1"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("speed").verdict)
    }

    @Test
    fun `a genuinely different FLOAT is still a mismatch`() {
        val specs = linkedMapOf("定位" to listOf(FieldSpec("speed", "速度", "", FieldType.FLOAT, "m/s")))
        val r = VerificationEngine.buildReport(
            configured = mapOf("speed" to "0.1"),
            observed = mapOf("speed" to "2.5"),
            specs = specs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("speed").verdict)
    }

    @Test
    fun `float normalisation does not leak into integer columns`() {
        // Guards the regression this whole comparison layer was tightened for: mnc=3 configured
        // against a real, unhooked "03" must remain a MISMATCH.
        val specs = linkedMapOf("x" to listOf(FieldSpec("mnc", "MNC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("mnc" to "3"),
            observed = mapOf("mnc" to "03"),
            specs = specs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("mnc").verdict)
    }

    private fun VerificationReport.field(col: String) =
        groups.flatMap { it.fields }.first { it.spec.dbColumn == col }
}
