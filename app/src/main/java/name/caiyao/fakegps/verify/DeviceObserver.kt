package name.caiyao.fakegps.verify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads back what THIS process observes through the same public Android APIs a target app calls,
 * keyed by the profile table's column names so it can be joined directly against the published
 * config (see [VerificationEngine]).
 *
 * Keying on dbColumn is the whole point: it is the one identifier shared by the field spec, the DB,
 * and the transport payload, so verification cannot drift away from the field model.
 *
 * Deliberately free of judgement — it reports values, never verdicts. All interpretation lives in
 * [VerificationEngine], which is pure and unit-tested.
 */
class DeviceObserver(private val context: Context) {

    data class Observation(
        /** dbColumn -> value as observed. Absent key == this process could not read the field. */
        val values: Map<String, String>,
        /** Human-readable reasons a category came back empty, shown so blank ≠ silent failure. */
        val notes: List<String>,
        val cellCount: Int,
    )

    fun observe(): Observation {
        val out = mutableMapOf<String, String>()
        val notes = mutableListOf<String>()
        var cellCount = 0

        readLocation(out, notes)
        cellCount = readCellular(out, notes)
        readTelephony(out, notes)
        readWifi(out, notes)
        readNetwork(out)

        return Observation(out, notes, cellCount)
    }

    // -- location -------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun readLocation(out: MutableMap<String, String>, notes: MutableList<String>) {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            notes += "定位：缺少 ACCESS_FINE_LOCATION 权限，无法读回"
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        if (loc == null) {
            notes += "定位：系统暂无最后已知位置"
            return
        }
        out["latitude"] = loc.latitude.toString()
        out["longitude"] = loc.longitude.toString()
        out["altitude"] = loc.altitude.toString()
        out["speed"] = loc.speed.toString()
        out["bearing"] = loc.bearing.toString()
        out["accuracy"] = loc.accuracy.toString()
    }

    // -- cellular -------------------------------------------------------------------------------

    /** @return number of cells the radio reported. */
    @SuppressLint("MissingPermission")
    private fun readCellular(out: MutableMap<String, String>, notes: MutableList<String>): Int {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            notes += "蜂窝：缺少 ACCESS_FINE_LOCATION 权限，无法读回"
            return 0
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return 0

        var cells = runCatching { tm.allCellInfo }.getOrNull().orEmpty()

        // getAllCellInfo() serves a throttled cache that is routinely empty right after process
        // start, which is indistinguishable from a broken hook. Force one fresh radio read before
        // concluding anything.
        if (cells.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cells = requestFreshCellInfo(tm)
        }

        if (cells.isEmpty()) {
            notes += "蜂窝：getAllCellInfo() 返回空 — 本设备/ROM 不向本进程提供实时 modem 数据，" +
                    "因此蜂窝字段在本页无法验证（不代表 hook 失败）"
            return 0
        }

        // The registered (serving) cell is the one the spoof targets; neighbours carry a separate
        // field (neighbor_cells_json) and would otherwise overwrite the serving values here.
        val serving = cells.firstOrNull { it.isRegistered } ?: cells.first()
        readServingCell(serving, out)
        return cells.size
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshCellInfo(tm: TelephonyManager): List<CellInfo> {
        val latch = CountDownLatch(1)
        val fresh = AtomicReference<List<CellInfo>>(emptyList())
        val executor = Executors.newSingleThreadExecutor()
        return try {
            tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    fresh.set(cellInfo); latch.countDown()
                }
                override fun onError(errorCode: Int, detail: Throwable?) {
                    latch.countDown()
                }
            })
            latch.await(3, TimeUnit.SECONDS)
            fresh.get()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readServingCell(cell: CellInfo, out: MutableMap<String, String>) {
        when (cell) {
            is CellInfoGsm -> {
                val id = cell.cellIdentity
                out.putStr("mcc", id.mccString)
                out.putStr("mnc", id.mncString)
                out.putInt("lac", id.lac)
                out.putInt("cid", id.cid)
                out.putInt("arfcn", id.arfcn)
                out.putInt("bsic", id.bsic)
                out.putInt("gsm_rssi", cell.cellSignalStrength.dbm)
                out.putInt("gsm_ber", cell.cellSignalStrength.bitErrorRate)
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                out.putStr("mcc", id.mccString)
                out.putStr("mnc", id.mncString)
                out.putInt("lac", id.lac)
                out.putInt("cid", id.cid)
                out.putInt("psc", id.psc)
                out.putInt("uarfcn", id.uarfcn)
                out.putInt("wcdma_rssi", cell.cellSignalStrength.dbm)
            }
            is CellInfoLte -> {
                val id = cell.cellIdentity
                out.putStr("mcc", id.mccString)
                out.putStr("mnc", id.mncString)
                out.putInt("tac", id.tac)
                out.putInt("ci", id.ci)
                out.putInt("pci", id.pci)
                out.putInt("earfcn", id.earfcn)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    out.putInt("lte_bandwidth", id.bandwidth)
                }
                val ss = cell.cellSignalStrength
                out.putInt("lte_rsrp", ss.rsrp)
                out.putInt("lte_rsrq", ss.rsrq)
                out.putInt("lte_rssi", ss.rssi)
                out.putInt("lte_cqi", ss.cqi)
                out.putInt("lte_ta", ss.timingAdvance)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    out.putInt("lte_sinr", ss.rssnr)
                }
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                    (cell.cellIdentity as? CellIdentityNr)?.let { id ->
                        out.putStr("mcc", id.mccString)
                        out.putStr("mnc", id.mncString)
                        out.putLong("nci", id.nci)
                        out.putInt("nr_pci", id.pci)
                        out.putInt("nrarfcn", id.nrarfcn)
                        out.putInt("nr_tac", id.tac)
                    }
                    (cell.cellSignalStrength as? CellSignalStrengthNr)?.let { ss ->
                        out.putInt("nr_ss_rsrp", ss.ssRsrp)
                        out.putInt("nr_ss_rsrq", ss.ssRsrq)
                        out.putInt("nr_ss_sinr", ss.ssSinr)
                        out.putInt("nr_csi_rsrp", ss.csiRsrp)
                        out.putInt("nr_csi_rsrq", ss.csiRsrq)
                        out.putInt("nr_csi_sinr", ss.csiSinr)
                    }
                }
            }
        }
    }

    // -- operator / service state ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun readTelephony(out: MutableMap<String, String>, notes: MutableList<String>) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        out.putStr("operator_name", tm.networkOperatorName)
        out.putStr("operator_numeric", tm.networkOperator)
        out.putStr("sim_operator", tm.simOperator)
        out.putStr("sim_operator_name", tm.simOperatorName)
        out.putStr("sim_country_iso", tm.simCountryIso)
        out.putStr("network_country_iso", tm.networkCountryIso)
        out["is_roaming"] = tm.isNetworkRoaming.toString()
        out["phone_type"] = tm.phoneType.toString()

        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            notes += "网络类型/服务状态：缺少 READ_PHONE_STATE 权限，无法读回"
            return
        }
        runCatching {
            out["data_network_type"] = tm.dataNetworkType.toString()
            out["voice_network_type"] = tm.voiceNetworkType.toString()
            out["data_state"] = tm.dataState.toString()
            out["data_activity"] = tm.dataActivity.toString()
            tm.serviceState?.let { out["service_state"] = it.state.toString() }
        }
    }

    // -- wifi / network -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun readWifi(out: MutableMap<String, String>, notes: MutableList<String>) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        out["wifi_enabled"] = wm.isWifiEnabled.toString()
        if (!wm.isWifiEnabled) {
            notes += "WiFi：已关闭，WiFi 字段无法读回"
            return
        }
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo
        if (info == null) {
            notes += "WiFi：未连接，WiFi 字段无法读回"
            return
        }
        @Suppress("DEPRECATION")
        run {
            out.putStr("wifi_ssid", info.ssid)
            out.putStr("wifi_bssid", info.bssid)
            out["wifi_rssi"] = info.rssi.toString()
            out["wifi_frequency"] = info.frequency.toString()
            out["wifi_link_speed"] = info.linkSpeed.toString()
            out.putStr("wifi_mac", info.macAddress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                out["wifi_tx_link_speed"] = info.txLinkSpeedMbps.toString()
                out["wifi_rx_link_speed"] = info.rxLinkSpeedMbps.toString()
                out["wifi_standard"] = info.wifiStandard.toString()
            }
        }
    }

    private fun readNetwork(out: MutableMap<String, String>) {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { !it.isLoopback && it.isUp }
                .forEach { ni ->
                    ni.inetAddresses.toList()
                        .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }
                        ?.let { addr ->
                            if (!out.containsKey("local_ipv4")) {
                                out["local_ipv4"] = addr.hostAddress!!
                                out["interface_name"] = ni.name
                            }
                        }
                }
        }
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) } ?: return@runCatching
            lp.dnsServers.getOrNull(0)?.hostAddress?.let { out["dns_primary"] = it }
            lp.dnsServers.getOrNull(1)?.hostAddress?.let { out["dns_secondary"] = it }
        }
    }

    // -- helpers --------------------------------------------------------------------------------

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun MutableMap<String, String>.putStr(key: String, value: String?) {
        if (!value.isNullOrBlank()) this[key] = value
    }

    /**
     * Android signals "no value" with [CellInfo.UNAVAILABLE] (== Integer.MAX_VALUE), not null.
     * Storing it verbatim would render as `2147483647` and read as a MISMATCH against whatever the
     * user configured — a fake failure. Absent is the honest representation.
     */
    private fun MutableMap<String, String>.putInt(key: String, value: Int) {
        if (value != Int.MAX_VALUE && value != CellInfo.UNAVAILABLE) this[key] = value.toString()
    }

    private fun MutableMap<String, String>.putLong(key: String, value: Long) {
        if (value != Long.MAX_VALUE && value != CellInfo.UNAVAILABLE.toLong()) this[key] = value.toString()
    }
}
