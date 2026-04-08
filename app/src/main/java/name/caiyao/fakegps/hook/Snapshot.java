package name.caiyao.fakegps.hook;

import android.database.Cursor;

import java.util.Random;

/**
 * Thread-safe snapshot of current spoofing configuration.
 * Published atomically via {@link MainHook#CURRENT}.
 *
 * ALL fields are nullable wrapper types:
 *   null  = passthrough (use real device value, don't hook)
 *   value = spoof with this value
 */
class Snapshot {

    /** Default: everything null = passthrough all real values. */
    static final Snapshot PASSTHROUGH = new Snapshot();

    // ==========================================
    // A. LOCATION (resolved by MainHook per hour)
    // ==========================================
    Double latitude;
    Double longitude;
    Double altitude;        // meters
    Float speed;            // m/s
    Float bearing;          // degrees
    Float accuracy;         // meters

    // ==========================================
    // B. CELL IDENTITY - GSM/2G
    // ==========================================
    Integer mcc;            // Mobile Country Code
    Integer mnc;            // Mobile Network Code
    Integer lac;            // Location Area Code
    Integer cid;            // Cell ID (GSM 0-65535)
    Integer arfcn;          // Absolute Radio Frequency Channel Number
    Integer bsic;           // Base Station Identity Code (0-63)

    // ==========================================
    // C. CELL IDENTITY - WCDMA/3G
    // ==========================================
    Integer psc;            // Primary Scrambling Code (0-511)
    Integer uarfcn;         // UTRA ARFCN

    // ==========================================
    // D. CELL IDENTITY - LTE/4G
    // ==========================================
    Integer tac;            // Tracking Area Code
    Integer ci;             // Cell Identity 28-bit
    Integer pci;            // Physical Cell ID (0-503)
    Integer earfcn;         // E-UTRA ARFCN
    Integer lteBandwidth;   // Bandwidth kHz

    // ==========================================
    // E. CELL IDENTITY - NR/5G
    // ==========================================
    Long nci;               // NR Cell Identity 36-bit
    Integer nrarfcn;        // NR ARFCN
    Integer nrPci;          // NR Physical Cell ID (0-1007)
    Integer nrTac;          // NR Tracking Area Code

    // ==========================================
    // F. SIGNAL - GSM
    // ==========================================
    Integer gsmRssi;        // -113 to -51 dBm
    Integer gsmBer;         // Bit Error Rate (0-7)
    Integer gsmTa;          // Timing Advance (0-219)

    // ==========================================
    // G. SIGNAL - WCDMA
    // ==========================================
    Integer wcdmaRssi;      // dBm
    Integer wcdmaRscp;      // -120 to -24 dBm
    Integer wcdmaEcno;      // -24 to 1 dB

    // ==========================================
    // H. SIGNAL - LTE
    // ==========================================
    Integer lteRssi;        // dBm
    Integer lteRsrp;        // -140 to -44 dBm
    Integer lteRsrq;        // -20 to -3 dB
    Integer lteSinr;        // -20 to +30 dB
    Integer lteCqi;         // Channel Quality Indicator (0-15)
    Integer lteTa;          // Timing Advance (0-1282)

    // ==========================================
    // I. SIGNAL - NR/5G
    // ==========================================
    Integer nrSsRsrp;      // -156 to -31 dBm
    Integer nrSsRsrq;      // -43 to 20 dB
    Integer nrSsSinr;       // -23 to 40 dB
    Integer nrCsiRsrp;
    Integer nrCsiRsrq;
    Integer nrCsiSinr;

    // ==========================================
    // J. SIGNAL FLUCTUATION
    // ==========================================
    Boolean signalFluctuationEnabled;
    Integer signalFluctuationRangeDb;   // e.g. 10 means +/-5dB

    // ==========================================
    // K. CARRIER & NETWORK
    // ==========================================
    Integer networkType;        // TelephonyManager.NETWORK_TYPE_*
    Integer dataNetworkType;
    Integer voiceNetworkType;
    String operatorName;        // e.g. "China Mobile"
    String operatorNumeric;     // e.g. "46000"
    String simOperator;
    String simOperatorName;
    String simCountryIso;       // e.g. "cn"
    String networkCountryIso;
    Boolean isRoaming;
    Integer phoneType;          // GSM=1, CDMA=2

    // ==========================================
    // L. SERVICE STATE
    // ==========================================
    Integer serviceState;       // 0=IN_SERVICE
    Integer dataState;          // 2=CONNECTED
    Integer dataActivity;       // 3=INOUT

    // ==========================================
    // M. DISPLAY INFO
    // ==========================================
    Integer overrideNetworkType;

    // ==========================================
    // N. PHYSICAL CHANNEL CONFIG
    // ==========================================
    Integer band;               // Band number (e.g. 1,3,7 for LTE; 41,77,78 for NR)
    Integer channelBandwidth;   // Bandwidth kHz (NOT band number)
    Integer cellBandwidthDownlink;
    Integer physicalCellId;

    // ==========================================
    // O. WIFI
    // ==========================================
    String wifiSsid;
    String wifiBssid;
    Integer wifiRssi;           // -40 to -90 dBm
    Integer wifiFrequency;      // MHz
    Integer wifiLinkSpeed;      // Mbps
    Integer wifiTxLinkSpeed;
    Integer wifiRxLinkSpeed;
    Integer wifiChannel;
    Integer wifiStandard;       // WIFI_STANDARD_*
    Integer wifiSecurityType;
    String wifiMac;
    String wifiIp;
    Boolean wifiHidden;
    Boolean wifiEnabled;

    // ==========================================
    // P. IP & CONNECTIVITY
    // ==========================================
    String localIpv4;
    String localIpv6;
    String dnsPrimary;
    String dnsSecondary;
    String gateway;
    String subnetMask;
    String connectionType;      // "WIFI" or "MOBILE"
    String interfaceName;       // "wlan0", "rmnet0"

    // ==========================================
    // Q. NEIGHBOR CELLS
    // ==========================================
    String neighborCellsJson;

    // ==========================================================
    // CONVENIENCE CHECKS
    // ==========================================================

    boolean hasLocation() { return latitude != null && longitude != null; }
    boolean hasGsmCell() { return lac != null && cid != null; }
    boolean hasLteCell() { return ci != null; }
    boolean hasNrCell() { return nci != null; }
    boolean hasWifi() { return wifiSsid != null || wifiBssid != null; }
    boolean hasPhysicalChannelConfig() {
        return band != null || channelBandwidth != null || cellBandwidthDownlink != null || physicalCellId != null;
    }

    /**
     * Apply signal fluctuation if enabled.
     * Returns baseValue +/- random offset within configured range.
     */
    int fluctuate(int baseValue, Random rnd) {
        if (signalFluctuationEnabled != null && signalFluctuationEnabled
                && signalFluctuationRangeDb != null && signalFluctuationRangeDb > 0) {
            int halfRange = signalFluctuationRangeDb / 2;
            return baseValue + rnd.nextInt(signalFluctuationRangeDb + 1) - halfRange;
        }
        return baseValue;
    }

    // ==========================================================
    // CURSOR READING (maps DB snake_case columns to fields)
    // ==========================================================

    static Snapshot fromCursor(Cursor c) {
        Snapshot s = new Snapshot();

        // A. Location
        s.latitude = getDouble(c, "latitude");
        s.longitude = getDouble(c, "longitude");
        s.altitude = getDouble(c, "altitude");
        s.speed = getFloat(c, "speed");
        s.bearing = getFloat(c, "bearing");
        s.accuracy = getFloat(c, "accuracy");

        // B. Cell Identity - GSM
        s.mcc = getInt(c, "mcc");
        s.mnc = getInt(c, "mnc");
        s.lac = getInt(c, "lac");
        s.cid = getInt(c, "cid");
        s.arfcn = getInt(c, "arfcn");
        s.bsic = getInt(c, "bsic");

        // C. Cell Identity - WCDMA
        s.psc = getInt(c, "psc");
        s.uarfcn = getInt(c, "uarfcn");

        // D. Cell Identity - LTE
        s.tac = getInt(c, "tac");
        s.ci = getInt(c, "ci");
        s.pci = getInt(c, "pci");
        s.earfcn = getInt(c, "earfcn");
        s.lteBandwidth = getInt(c, "lte_bandwidth");

        // E. Cell Identity - NR
        s.nci = getLong(c, "nci");
        s.nrarfcn = getInt(c, "nrarfcn");
        s.nrPci = getInt(c, "nr_pci");
        s.nrTac = getInt(c, "nr_tac");

        // F. Signal - GSM
        s.gsmRssi = getInt(c, "gsm_rssi");
        s.gsmBer = getInt(c, "gsm_ber");
        s.gsmTa = getInt(c, "gsm_ta");

        // G. Signal - WCDMA
        s.wcdmaRssi = getInt(c, "wcdma_rssi");
        s.wcdmaRscp = getInt(c, "wcdma_rscp");
        s.wcdmaEcno = getInt(c, "wcdma_ecno");

        // H. Signal - LTE
        s.lteRssi = getInt(c, "lte_rssi");
        s.lteRsrp = getInt(c, "lte_rsrp");
        s.lteRsrq = getInt(c, "lte_rsrq");
        s.lteSinr = getInt(c, "lte_sinr");
        s.lteCqi = getInt(c, "lte_cqi");
        s.lteTa = getInt(c, "lte_ta");

        // I. Signal - NR
        s.nrSsRsrp = getInt(c, "nr_ss_rsrp");
        s.nrSsRsrq = getInt(c, "nr_ss_rsrq");
        s.nrSsSinr = getInt(c, "nr_ss_sinr");
        s.nrCsiRsrp = getInt(c, "nr_csi_rsrp");
        s.nrCsiRsrq = getInt(c, "nr_csi_rsrq");
        s.nrCsiSinr = getInt(c, "nr_csi_sinr");

        // J. Signal Fluctuation
        s.signalFluctuationEnabled = getBool(c, "signal_fluctuation_enabled");
        s.signalFluctuationRangeDb = getInt(c, "signal_fluctuation_range_db");

        // K. Carrier & Network
        s.networkType = getInt(c, "network_type");
        s.dataNetworkType = getInt(c, "data_network_type");
        s.voiceNetworkType = getInt(c, "voice_network_type");
        s.operatorName = getString(c, "operator_name");
        s.operatorNumeric = getString(c, "operator_numeric");
        s.simOperator = getString(c, "sim_operator");
        s.simOperatorName = getString(c, "sim_operator_name");
        s.simCountryIso = getString(c, "sim_country_iso");
        s.networkCountryIso = getString(c, "network_country_iso");
        s.isRoaming = getBool(c, "is_roaming");
        s.phoneType = getInt(c, "phone_type");

        // L. Service State
        s.serviceState = getInt(c, "service_state");
        s.dataState = getInt(c, "data_state");
        s.dataActivity = getInt(c, "data_activity");

        // M. Display Info
        s.overrideNetworkType = getInt(c, "override_network_type");

        // N. Physical Channel Config
        s.band = getInt(c, "band");
        s.channelBandwidth = getInt(c, "channel_bandwidth");
        s.cellBandwidthDownlink = getInt(c, "cell_bandwidth_downlink");
        s.physicalCellId = getInt(c, "physical_cell_id");

        // O. WiFi
        s.wifiSsid = getString(c, "wifi_ssid");
        s.wifiBssid = getString(c, "wifi_bssid");
        s.wifiRssi = getInt(c, "wifi_rssi");
        s.wifiFrequency = getInt(c, "wifi_frequency");
        s.wifiLinkSpeed = getInt(c, "wifi_link_speed");
        s.wifiTxLinkSpeed = getInt(c, "wifi_tx_link_speed");
        s.wifiRxLinkSpeed = getInt(c, "wifi_rx_link_speed");
        s.wifiChannel = getInt(c, "wifi_channel");
        s.wifiStandard = getInt(c, "wifi_standard");
        s.wifiSecurityType = getInt(c, "wifi_security_type");
        s.wifiMac = getString(c, "wifi_mac");
        s.wifiIp = getString(c, "wifi_ip");
        s.wifiHidden = getBool(c, "wifi_hidden");
        s.wifiEnabled = getBool(c, "wifi_enabled");

        // P. IP & Connectivity
        s.localIpv4 = getString(c, "local_ipv4");
        s.localIpv6 = getString(c, "local_ipv6");
        s.dnsPrimary = getString(c, "dns_primary");
        s.dnsSecondary = getString(c, "dns_secondary");
        s.gateway = getString(c, "gateway");
        s.subnetMask = getString(c, "subnet_mask");
        s.connectionType = getString(c, "connection_type");
        s.interfaceName = getString(c, "interface_name");

        // Q. Neighbor cells
        s.neighborCellsJson = getString(c, "neighbor_cells_json");

        return s;
    }

    // --- Null-safe cursor column readers ---

    private static Double getDouble(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return (idx >= 0 && !c.isNull(idx)) ? c.getDouble(idx) : null;
    }

    private static Float getFloat(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return (idx >= 0 && !c.isNull(idx)) ? c.getFloat(idx) : null;
    }

    private static Integer getInt(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return (idx >= 0 && !c.isNull(idx)) ? c.getInt(idx) : null;
    }

    private static Long getLong(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return (idx >= 0 && !c.isNull(idx)) ? c.getLong(idx) : null;
    }

    private static String getString(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return (idx >= 0 && !c.isNull(idx)) ? c.getString(idx) : null;
    }

    private static Boolean getBool(Cursor c, String col) {
        Integer val = getInt(c, col);
        return (val != null) ? (val != 0) : null;
    }
}
