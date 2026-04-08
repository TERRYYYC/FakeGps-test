package name.caiyao.fakegps.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

public class DbHelper extends SQLiteOpenHelper {

    private static final String TAG = "FakeGPS_DB";
    private static final String DB_NAME = "applist.db";
    public static final String APP_TABLE_NAME = "app";
    public static final String APP_TEMP_NAME = "temp";
    private static final int DB_VERSION = 2;  // Bumped from 1 for MockProfile fields

    private SQLiteDatabase mdb;

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TEMP_TABLE);
        db.execSQL(CREATE_APP_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Add all new MockProfile columns to existing tables
            for (String col : MIGRATION_V2_COLUMNS) {
                safeAddColumn(db, APP_TEMP_NAME, col);
                safeAddColumn(db, APP_TABLE_NAME, col);
            }
        }
    }

    private void safeAddColumn(SQLiteDatabase db, String table, String columnDef) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + columnDef);
        } catch (Exception e) {
            // Column may already exist
            Log.d(TAG, "Column may already exist in " + table + ": " + columnDef);
        }
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        return getOrCreateExternalDb();
    }

    @Override
    public SQLiteDatabase getWritableDatabase() {
        return getOrCreateExternalDb();
    }

    private SQLiteDatabase getOrCreateExternalDb() {
        if (mdb != null && mdb.isOpen()) {
            return mdb;
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Log.e(TAG, "External storage not mounted");
            return null;
        }

        String dbPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/database";
        String dbFile = dbPath + "/applist.db";

        File dir = new File(dbPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = new File(dbFile);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Failed to create database file", e);
            }
        }

        mdb = SQLiteDatabase.openOrCreateDatabase(file, null);
        return mdb;
    }

    // =====================================================
    // TABLE DEFINITIONS
    // =====================================================

    private static final String CREATE_TEMP_TABLE =
            "CREATE TABLE IF NOT EXISTS " + APP_TEMP_NAME + "("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "latitude DOUBLE,"
            + "longitude DOUBLE,"
            + "lac INTEGER,"
            + "cid INTEGER,"
            + "addname VARCHAR(80),"
            // Location extensions
            + "altitude DOUBLE,"
            + "speed REAL,"
            + "bearing REAL,"
            + "accuracy REAL,"
            // Cell Identity — common
            + "mcc INTEGER,"
            + "mnc INTEGER,"
            + "arfcn INTEGER,"
            + "bsic INTEGER,"
            // Cell Identity — WCDMA
            + "psc INTEGER,"
            + "uarfcn INTEGER,"
            // Cell Identity — LTE
            + "tac INTEGER,"
            + "ci INTEGER,"
            + "pci INTEGER,"
            + "earfcn INTEGER,"
            + "lte_bandwidth INTEGER,"
            // Cell Identity — NR/5G
            + "nci INTEGER,"
            + "nrarfcn INTEGER,"
            + "nr_pci INTEGER,"
            + "nr_tac INTEGER,"
            // Signal — GSM
            + "gsm_rssi INTEGER,"
            + "gsm_ber INTEGER,"
            + "gsm_ta INTEGER,"
            // Signal — WCDMA
            + "wcdma_rssi INTEGER,"
            + "wcdma_rscp INTEGER,"
            + "wcdma_ecno INTEGER,"
            // Signal — LTE
            + "lte_rssi INTEGER,"
            + "lte_rsrp INTEGER,"
            + "lte_rsrq INTEGER,"
            + "lte_sinr INTEGER,"
            + "lte_cqi INTEGER,"
            + "lte_ta INTEGER,"
            // Signal — NR
            + "nr_ss_rsrp INTEGER,"
            + "nr_ss_rsrq INTEGER,"
            + "nr_ss_sinr INTEGER,"
            + "nr_csi_rsrp INTEGER,"
            + "nr_csi_rsrq INTEGER,"
            + "nr_csi_sinr INTEGER,"
            // Signal fluctuation
            + "signal_fluctuation_enabled INTEGER,"
            + "signal_fluctuation_range_db INTEGER,"
            // Carrier & Network
            + "network_type INTEGER,"
            + "data_network_type INTEGER,"
            + "voice_network_type INTEGER,"
            + "operator_name TEXT,"
            + "operator_numeric TEXT,"
            + "sim_operator TEXT,"
            + "sim_operator_name TEXT,"
            + "sim_country_iso TEXT,"
            + "network_country_iso TEXT,"
            + "is_roaming INTEGER,"
            + "phone_type INTEGER,"
            // Service State
            + "service_state INTEGER,"
            + "data_state INTEGER,"
            + "data_activity INTEGER,"
            // Display Info
            + "override_network_type INTEGER,"
            // Physical Channel Config
            + "band INTEGER,"
            + "channel_bandwidth INTEGER,"
            + "cell_bandwidth_downlink INTEGER,"
            + "physical_cell_id INTEGER,"
            // WiFi
            + "wifi_ssid TEXT,"
            + "wifi_bssid TEXT,"
            + "wifi_rssi INTEGER,"
            + "wifi_frequency INTEGER,"
            + "wifi_link_speed INTEGER,"
            + "wifi_tx_link_speed INTEGER,"
            + "wifi_rx_link_speed INTEGER,"
            + "wifi_channel INTEGER,"
            + "wifi_standard INTEGER,"
            + "wifi_security_type INTEGER,"
            + "wifi_mac TEXT,"
            + "wifi_ip TEXT,"
            + "wifi_hidden INTEGER,"
            + "wifi_enabled INTEGER,"
            // IP & Connectivity
            + "local_ipv4 TEXT,"
            + "local_ipv6 TEXT,"
            + "dns_primary TEXT,"
            + "dns_secondary TEXT,"
            + "gateway TEXT,"
            + "subnet_mask TEXT,"
            + "connection_type TEXT,"
            + "interface_name TEXT,"
            // Neighboring cells
            + "neighbor_cells_json TEXT"
            + ")";

    private static final String CREATE_APP_TABLE =
            "CREATE TABLE IF NOT EXISTS " + APP_TABLE_NAME + "("
            + "package_name TEXT PRIMARY KEY,"
            + "latitude DOUBLE,"
            + "longitude DOUBLE,"
            + "lac INTEGER,"
            + "cid INTEGER,"
            + "addname VARCHAR(80),"
            // Same extension columns as temp table
            + "altitude DOUBLE,"
            + "speed REAL,"
            + "bearing REAL,"
            + "accuracy REAL,"
            + "mcc INTEGER,"
            + "mnc INTEGER,"
            + "arfcn INTEGER,"
            + "bsic INTEGER,"
            + "psc INTEGER,"
            + "uarfcn INTEGER,"
            + "tac INTEGER,"
            + "ci INTEGER,"
            + "pci INTEGER,"
            + "earfcn INTEGER,"
            + "lte_bandwidth INTEGER,"
            + "nci INTEGER,"
            + "nrarfcn INTEGER,"
            + "nr_pci INTEGER,"
            + "nr_tac INTEGER,"
            + "gsm_rssi INTEGER,"
            + "gsm_ber INTEGER,"
            + "gsm_ta INTEGER,"
            + "wcdma_rssi INTEGER,"
            + "wcdma_rscp INTEGER,"
            + "wcdma_ecno INTEGER,"
            + "lte_rssi INTEGER,"
            + "lte_rsrp INTEGER,"
            + "lte_rsrq INTEGER,"
            + "lte_sinr INTEGER,"
            + "lte_cqi INTEGER,"
            + "lte_ta INTEGER,"
            + "nr_ss_rsrp INTEGER,"
            + "nr_ss_rsrq INTEGER,"
            + "nr_ss_sinr INTEGER,"
            + "nr_csi_rsrp INTEGER,"
            + "nr_csi_rsrq INTEGER,"
            + "nr_csi_sinr INTEGER,"
            + "signal_fluctuation_enabled INTEGER,"
            + "signal_fluctuation_range_db INTEGER,"
            + "network_type INTEGER,"
            + "data_network_type INTEGER,"
            + "voice_network_type INTEGER,"
            + "operator_name TEXT,"
            + "operator_numeric TEXT,"
            + "sim_operator TEXT,"
            + "sim_operator_name TEXT,"
            + "sim_country_iso TEXT,"
            + "network_country_iso TEXT,"
            + "is_roaming INTEGER,"
            + "phone_type INTEGER,"
            + "service_state INTEGER,"
            + "data_state INTEGER,"
            + "data_activity INTEGER,"
            + "override_network_type INTEGER,"
            + "band INTEGER,"
            + "channel_bandwidth INTEGER,"
            + "cell_bandwidth_downlink INTEGER,"
            + "physical_cell_id INTEGER,"
            + "wifi_ssid TEXT,"
            + "wifi_bssid TEXT,"
            + "wifi_rssi INTEGER,"
            + "wifi_frequency INTEGER,"
            + "wifi_link_speed INTEGER,"
            + "wifi_tx_link_speed INTEGER,"
            + "wifi_rx_link_speed INTEGER,"
            + "wifi_channel INTEGER,"
            + "wifi_standard INTEGER,"
            + "wifi_security_type INTEGER,"
            + "wifi_mac TEXT,"
            + "wifi_ip TEXT,"
            + "wifi_hidden INTEGER,"
            + "wifi_enabled INTEGER,"
            + "local_ipv4 TEXT,"
            + "local_ipv6 TEXT,"
            + "dns_primary TEXT,"
            + "dns_secondary TEXT,"
            + "gateway TEXT,"
            + "subnet_mask TEXT,"
            + "connection_type TEXT,"
            + "interface_name TEXT,"
            + "neighbor_cells_json TEXT"
            + ")";

    // Columns added in migration from v1 to v2
    private static final String[] MIGRATION_V2_COLUMNS = {
            "altitude DOUBLE",
            "speed REAL",
            "bearing REAL",
            "accuracy REAL",
            "mcc INTEGER",
            "mnc INTEGER",
            "arfcn INTEGER",
            "bsic INTEGER",
            "psc INTEGER",
            "uarfcn INTEGER",
            "tac INTEGER",
            "ci INTEGER",
            "pci INTEGER",
            "earfcn INTEGER",
            "lte_bandwidth INTEGER",
            "nci INTEGER",
            "nrarfcn INTEGER",
            "nr_pci INTEGER",
            "nr_tac INTEGER",
            "gsm_rssi INTEGER",
            "gsm_ber INTEGER",
            "gsm_ta INTEGER",
            "wcdma_rssi INTEGER",
            "wcdma_rscp INTEGER",
            "wcdma_ecno INTEGER",
            "lte_rssi INTEGER",
            "lte_rsrp INTEGER",
            "lte_rsrq INTEGER",
            "lte_sinr INTEGER",
            "lte_cqi INTEGER",
            "lte_ta INTEGER",
            "nr_ss_rsrp INTEGER",
            "nr_ss_rsrq INTEGER",
            "nr_ss_sinr INTEGER",
            "nr_csi_rsrp INTEGER",
            "nr_csi_rsrq INTEGER",
            "nr_csi_sinr INTEGER",
            "signal_fluctuation_enabled INTEGER",
            "signal_fluctuation_range_db INTEGER",
            "network_type INTEGER",
            "data_network_type INTEGER",
            "voice_network_type INTEGER",
            "operator_name TEXT",
            "operator_numeric TEXT",
            "sim_operator TEXT",
            "sim_operator_name TEXT",
            "sim_country_iso TEXT",
            "network_country_iso TEXT",
            "is_roaming INTEGER",
            "phone_type INTEGER",
            "service_state INTEGER",
            "data_state INTEGER",
            "data_activity INTEGER",
            "override_network_type INTEGER",
            "band INTEGER",
            "channel_bandwidth INTEGER",
            "cell_bandwidth_downlink INTEGER",
            "physical_cell_id INTEGER",
            "wifi_ssid TEXT",
            "wifi_bssid TEXT",
            "wifi_rssi INTEGER",
            "wifi_frequency INTEGER",
            "wifi_link_speed INTEGER",
            "wifi_tx_link_speed INTEGER",
            "wifi_rx_link_speed INTEGER",
            "wifi_channel INTEGER",
            "wifi_standard INTEGER",
            "wifi_security_type INTEGER",
            "wifi_mac TEXT",
            "wifi_ip TEXT",
            "wifi_hidden INTEGER",
            "wifi_enabled INTEGER",
            "local_ipv4 TEXT",
            "local_ipv6 TEXT",
            "dns_primary TEXT",
            "dns_secondary TEXT",
            "gateway TEXT",
            "subnet_mask TEXT",
            "connection_type TEXT",
            "interface_name TEXT",
            "neighbor_cells_json TEXT"
    };
}
