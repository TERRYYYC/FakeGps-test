package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Before any verdict is believable, the hook must actually be applying the payload.
 *
 * Without this gate the screen reports "N 个字段未生效" in situations where passthrough is the
 * DESIGNED behaviour — sending the user to debug a module that is working exactly as configured.
 * Mirrors the gates in MainHook#loadSnapshot.
 */
class HookApplicabilityTest {

    @Test
    fun `always_on with a compatible payload is applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of(mode = "always_on", schemaVersion = 2, currentHour = 3),
        )
    }

    @Test
    fun `mode off means the hook passes everything through by design`() {
        assertEquals(
            HookApplicability.MODE_OFF,
            HookApplicability.of(mode = "off", schemaVersion = 2, currentHour = 3),
        )
    }

    @Test
    fun `inside the active window time_based is applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("time_based", 2, currentHour = 9, activeStart = 7, activeEnd = 22),
        )
    }

    @Test
    fun `outside the active window the hook passes through by design`() {
        // 23:00 with a 07:00-22:00 window. Previously every configured field read back real and the
        // screen blamed the module scope.
        assertEquals(
            HookApplicability.OUTSIDE_ACTIVE_HOURS,
            HookApplicability.of("time_based", 2, currentHour = 23, activeStart = 7, activeEnd = 22),
        )
    }

    @Test
    fun `active window wrapping past midnight is handled like the hook does`() {
        // MainHook: (start <= end) ? (h >= start && h < end) : (h >= start || h < end)
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("time_based", 2, currentHour = 2, activeStart = 22, activeEnd = 7),
        )
        assertEquals(
            HookApplicability.OUTSIDE_ACTIVE_HOURS,
            HookApplicability.of("time_based", 2, currentHour = 12, activeStart = 22, activeEnd = 7),
        )
    }

    @Test
    fun `time_based without an active window falls back to applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("time_based", 2, currentHour = 12),
        )
    }

    @Test
    fun `an incompatible schema version means the hook rejects this payload entirely`() {
        assertEquals(
            HookApplicability.SCHEMA_REJECTED,
            HookApplicability.of(mode = "always_on", schemaVersion = 1, currentHour = 3),
        )
    }

    @Test
    fun `schema rejection outranks the active window because the payload never loads`() {
        assertEquals(
            HookApplicability.SCHEMA_REJECTED,
            HookApplicability.of("time_based", 99, currentHour = 23, activeStart = 7, activeEnd = 22),
        )
    }

    @Test
    fun `only APPLYING allows verdicts to be trusted`() {
        assertEquals(true, HookApplicability.APPLYING.verdictsMeaningful)
        assertEquals(false, HookApplicability.MODE_OFF.verdictsMeaningful)
        assertEquals(false, HookApplicability.OUTSIDE_ACTIVE_HOURS.verdictsMeaningful)
        assertEquals(false, HookApplicability.SCHEMA_REJECTED.verdictsMeaningful)
    }
}
