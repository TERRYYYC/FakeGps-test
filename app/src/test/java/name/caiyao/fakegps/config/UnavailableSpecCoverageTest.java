package name.caiyao.fakegps.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import name.caiyao.fakegps.data.model.FieldSpec;
import org.junit.Test;

/**
 * COMPLETENESS GATE for the "--" decision set (reviewer P2, second pass).
 *
 * <p>The first version of this gate was fake coverage: {@code everyEditableColumn_isClassified()}
 * was tautologically true because {@code kindOf()} never returns null, and nothing walked the
 * classification sets in reverse. A newly added {@code FieldSpec} would silently become
 * UNSUPPORTED while the suite stayed green — the exact opposite of "nothing can be forgotten".
 *
 * <p>This version asserts a STRICT TWO-WAY EQUALITY between the editable field set and the
 * explicit decision set, so:
 * <ul>
 *   <li>adding a field to {@code FieldSpec} without deciding its "--" behaviour fails;</li>
 *   <li>leaving a decision behind for a field that no longer exists fails.</li>
 * </ul>
 */
public class UnavailableSpecCoverageTest {

    private static List<String> allEditableColumns() {
        List<String> cols = new ArrayList<>();
        for (Map.Entry<String, List<FieldSpec>> e : FieldSpec.Companion.allCategories().entrySet()) {
            for (FieldSpec f : e.getValue()) {
                cols.add(f.getDbColumn());
            }
        }
        return cols;
    }

    @Test
    public void editableColumns_haveNoDuplicates() {
        List<String> cols = allEditableColumns();
        assertEquals("duplicate column(s) in FieldSpec.allCategories()",
                cols.size(), new HashSet<>(cols).size());
    }

    /**
     * THE gate: the decision set and the editable set must be identical. Any drift in either
     * direction is a defect, and the failure message names the offending fields.
     */
    @Test
    public void decisionSet_exactlyCoversEditableFields() {
        Set<String> editable = new TreeSet<>(allEditableColumns());
        Set<String> decided = new TreeSet<>(UnavailableSpec.decidedColumns());

        Set<String> undecided = new TreeSet<>(editable);
        undecided.removeAll(decided);
        assertTrue("editable field(s) with no '--' decision recorded: " + undecided,
                undecided.isEmpty());

        Set<String> stale = new TreeSet<>(decided);
        stale.removeAll(editable);
        assertTrue("decision(s) recorded for non-existent field(s): " + stale, stale.isEmpty());
    }

    /** Reverse walk: nothing may be cleared for "--" that isn't a real editable field. */
    @Test
    public void supportedColumns_areAllEditable() {
        Set<String> editable = new HashSet<>(allEditableColumns());
        for (String col : UnavailableSpec.supportedColumns()) {
            assertTrue("supported column is not an editable field: " + col, editable.contains(col));
        }
    }

    /** Every unsupported field must carry a reason — an unexplained refusal is a silent gap. */
    @Test
    public void unsupportedFields_allHaveAReason() {
        for (String col : allEditableColumns()) {
            if (!UnavailableSpec.supportsUnavailable(col)) {
                assertFalse("no reason recorded for unsupported field: " + col,
                        UnavailableSpec.reasonFor(col).isEmpty());
            }
        }
    }

    /** FAIL-CLOSED: an unknown/typo'd column must never inherit capability. */
    @Test
    public void unknownColumns_failClosed() {
        assertEquals(UnavailableSpec.Kind.UNSUPPORTED, UnavailableSpec.kindOf("tacc"));
        assertEquals(UnavailableSpec.Kind.UNSUPPORTED, UnavailableSpec.kindOf("brand_new"));
        assertEquals(UnavailableSpec.Kind.UNSUPPORTED, UnavailableSpec.kindOf(""));
        assertEquals(UnavailableSpec.Kind.UNSUPPORTED, UnavailableSpec.kindOf(null));
    }

    /**
     * lac/cid reach BOTH {@code CellIdentityGsm} (unknown = MAX_VALUE) and
     * {@code GsmCellLocation.setLacAndCid()} (unknown = -1). Until the surface resolver exists,
     * a single folded sentinel would be illegal on one of them.
     */
    @Test
    public void dualSurfaceFields_areNotClearedYet() {
        assertFalse("lac is written to two surfaces with different unknowns",
                UnavailableSpec.supportsUnavailable("lac"));
        assertFalse("cid is written to two surfaces with different unknowns",
                UnavailableSpec.supportsUnavailable("cid"));
        assertTrue(UnavailableSpec.reasonFor("lac").contains("GsmCellLocation"));
    }

    /** PhysicalChannelConfig unknowns are 0 / -1, never CellInfo.UNAVAILABLE. */
    @Test
    public void physicalChannelConfigFields_areNotCellularSentinels() {
        for (String col : new String[] {
                "band", "channel_bandwidth", "cell_bandwidth_downlink", "physical_cell_id"}) {
            assertFalse(col + " unknown is 0 or -1, not CellInfo.UNAVAILABLE",
                    UnavailableSpec.supportsUnavailable(col));
        }
    }

    /** Wi-Fi integers: RSSI unknown is -127, frequency / link speed are -1. */
    @Test
    public void wifiIntegers_areNotCleared() {
        for (String col : new String[] {
                "wifi_rssi", "wifi_frequency", "wifi_link_speed", "wifi_tx_link_speed",
                "wifi_rx_link_speed", "wifi_channel", "wifi_standard", "wifi_security_type"}) {
            assertFalse(col + " must not inherit a cellular sentinel",
                    UnavailableSpec.supportsUnavailable(col));
        }
    }

    /** Network/service state integers use 0-based UNKNOWN constants. */
    @Test
    public void networkStateIntegers_areNotCleared() {
        for (String col : new String[] {
                "network_type", "data_network_type", "voice_network_type", "phone_type",
                "service_state", "data_state", "data_activity", "override_network_type"}) {
            assertFalse(col + " unknown is a 0-based constant", UnavailableSpec.supportsUnavailable(col));
        }
    }

    /** SSID unknown is "&lt;unknown ssid&gt;", BSSID is null — neither is the empty string. */
    @Test
    public void wifiIdentityText_isNotCleared() {
        assertFalse(UnavailableSpec.supportsUnavailable("wifi_ssid"));
        assertFalse(UnavailableSpec.supportsUnavailable("wifi_bssid"));
    }

    @Test
    public void locationAndBooleans_areUnsupported() {
        for (String col : new String[] {"latitude", "longitude", "altitude", "speed",
                "bearing", "accuracy", "is_roaming", "wifi_enabled", "wifi_hidden",
                "signal_fluctuation_enabled"}) {
            assertFalse(col, UnavailableSpec.supportsUnavailable(col));
        }
    }

    /** Cellular identity/signal integers and operator text ARE cleared. */
    @Test
    public void verifiedCellularAndOperatorFields_areCleared() {
        for (String col : new String[] {
                "tac", "ci", "pci", "earfcn", "mcc", "mnc", "nci",
                "lte_rsrp", "lte_rsrq", "lte_sinr", "operator_name", "sim_operator"}) {
            assertTrue(col + " should support --", UnavailableSpec.supportsUnavailable(col));
        }
    }
}
