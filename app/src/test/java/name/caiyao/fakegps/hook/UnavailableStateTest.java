package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import name.caiyao.fakegps.config.UnavailableSpec;
import org.junit.Test;

/**
 * Locks the third profile state ("--" = report as unavailable).
 *
 * <p>Two invariants live here:
 * <ol>
 *   <li>the field capability table classifies each column by how (or whether) it can express
 *       "no data" — a blanket sentinel would emit illegal values for text/boolean/location;</li>
 *   <li>arithmetic on field values must never be applied to a sentinel — jittering
 *       {@code Integer.MAX_VALUE} overflows into a plausible-looking negative reading, i.e.
 *       silently converts "no data" into fake data (reviewer constraint 3).</li>
 * </ol>
 */
public class UnavailableStateTest {

    // --- 1. Field capability table ---

    @Test
    public void cellularIntegerFields_useIntSentinel() {
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("tac"));
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("lte_rsrp"));
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("mcc"));
    }

    @Test
    public void nci_usesLongSentinel() {
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("nci"));
    }

    /** Only the operator/SIM group uses "" — Wi-Fi identity text has different unknowns. */
    @Test
    public void operatorTextFields_useEmptyString() {
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("operator_name"));
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("sim_operator"));
    }

    /** Location geometry has no "unavailable" form — the UI must not offer "--" there. */
    @Test
    public void locationFields_areUnsupported() {
        assertFalse(UnavailableSpec.supportsUnavailable("latitude"));
        assertFalse(UnavailableSpec.supportsUnavailable("longitude"));
        assertFalse(UnavailableSpec.supportsUnavailable("accuracy"));
    }

    /** A boolean has no third value; accepting "--" would make the UI lie. */
    @Test
    public void booleanFields_areUnsupported() {
        assertFalse(UnavailableSpec.supportsUnavailable("is_roaming"));
        assertFalse(UnavailableSpec.supportsUnavailable("wifi_enabled"));
    }

    @Test
    public void cellularAndTextFields_supportUnavailable() {
        assertTrue(UnavailableSpec.supportsUnavailable("tac"));
        assertTrue(UnavailableSpec.supportsUnavailable("nci"));
        assertTrue(UnavailableSpec.supportsUnavailable("operator_name"));
    }

    /** Non-cellular groups stay unavailable-incapable rather than guessing a sentinel. */
    @Test
    public void unverifiedGroups_failClosed() {
        assertFalse("Wi-Fi RSSI unknown is -127, not MAX_VALUE",
                UnavailableSpec.supportsUnavailable("wifi_rssi"));
        assertFalse("SSID unknown is \"<unknown ssid>\", not \"\"",
                UnavailableSpec.supportsUnavailable("wifi_ssid"));
    }

    // --- 2. Fluctuation must not corrupt a sentinel ---

    /**
     * RED before the fix: {@code MAX_VALUE + jitter} overflows to a large negative number,
     * which reads as a perfectly plausible (and completely fabricated) signal level.
     */
    @Test
    public void fluctuation_leavesUnavailableSentinelIntact() {
        Snapshot s = new Snapshot();
        s.signalFluctuationEnabled = true;
        s.signalFluctuationRangeDb = 10;

        int out = s.fluctuate(UnavailableSpec.UNAVAILABLE_INT, new Random(42));

        assertEquals("a sentinel must pass through fluctuation untouched",
                UnavailableSpec.UNAVAILABLE_INT, out);
        assertTrue("must never overflow into a negative 'measurement'", out > 0);
    }

    /** Real measurements must still jitter — the guard must not disable fluctuation wholesale. */
    @Test
    public void fluctuation_stillAppliesToRealValues() {
        Snapshot s = new Snapshot();
        s.signalFluctuationEnabled = true;
        s.signalFluctuationRangeDb = 10;

        int out = s.fluctuate(-85, new Random(42));

        assertTrue("expected -85 +/- 5, got " + out, out >= -90 && out <= -80);
    }

    @Test
    public void snapshotMaterializesCanonicalHookValuesAndRetainsDecisionSet() {
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(
                "lac", "operator_name", "network_type", "data_state",
                "band", "physical_cell_id", "lte_rsrp"));

        Snapshot s = Snapshot.from(new EmptySource(), selected);

        assertEquals(Integer.valueOf(Integer.MAX_VALUE), s.lac);
        assertEquals("", s.operatorName);
        assertEquals(Integer.valueOf(0), s.networkType);
        assertEquals(Integer.valueOf(0), s.dataState);
        assertEquals(Integer.valueOf(0), s.band);
        assertEquals(Integer.valueOf(-1), s.physicalCellId);
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), s.lteRsrp);
        assertTrue(s.isUnavailable("lac"));
        assertTrue(s.isUnavailable("operator_name"));

        selected.clear();
        assertTrue("snapshot must defensively copy the decision set", s.isUnavailable("lac"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void snapshotRejectsUnsupportedUnavailableField() {
        Snapshot.from(new EmptySource(), java.util.Collections.singleton("is_roaming"));
    }

    @Test
    public void exceptionalSurfacesDoNotReuseSnapshotSentinel() {
        assertEquals(Integer.valueOf(-1),
                Snapshot.resolveGsmCellLocationField(Integer.MAX_VALUE, 42, true));
        assertEquals(Integer.valueOf(42),
                Snapshot.resolveGsmCellLocationField(null, 42, false));
        org.junit.Assert.assertNull(
                Snapshot.resolvePlmnString(Integer.MAX_VALUE, "460", true));
        assertEquals("460", Snapshot.resolvePlmnString(null, "460", false));
        assertEquals("310", Snapshot.resolvePlmnString(310, "460", false));
    }

    private static final class EmptySource implements Snapshot.FieldSource {
        @Override public Double getDouble(String col) { return null; }
        @Override public Float getFloat(String col) { return null; }
        @Override public Integer getInt(String col) { return null; }
        @Override public Long getLong(String col) { return null; }
        @Override public String getString(String col) { return null; }
        @Override public Boolean getBool(String col) { return null; }
    }
}
