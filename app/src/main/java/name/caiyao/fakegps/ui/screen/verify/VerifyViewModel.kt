package name.caiyao.fakegps.ui.screen.verify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface

data class VerifyItem(
    val category: String,
    val label: String,
    val value: String,
)

class VerifyViewModel(app: Application) : AndroidViewModel(app) {

    private val _items = MutableStateFlow<List<VerifyItem>>(emptyList())
    val items: StateFlow<List<VerifyItem>> = _items

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val ctx = getApplication<Application>()
            val result = mutableListOf<VerifyItem>()

            result.addAll(readLocation(ctx))
            result.addAll(readCellInfo(ctx))
            result.addAll(readTelephony(ctx))
            result.addAll(readWifi(ctx))
            result.addAll(readNetwork(ctx))

            _items.value = result
            _isLoading.value = false
        }
    }

    private fun hasPermission(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun readLocation(ctx: Context): List<VerifyItem> {
        val cat = "定位"
        if (!hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return listOf(VerifyItem(cat, "状态", "缺少定位权限"))
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return listOf(VerifyItem(cat, "状态", "LocationManager 不可用"))

        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        return if (loc != null) listOf(
            VerifyItem(cat, "纬度", "%.6f".format(loc.latitude)),
            VerifyItem(cat, "经度", "%.6f".format(loc.longitude)),
            VerifyItem(cat, "海拔", "%.1f m".format(loc.altitude)),
            VerifyItem(cat, "速度", "%.1f m/s".format(loc.speed)),
            VerifyItem(cat, "方向", "%.1f\u00b0".format(loc.bearing)),
            VerifyItem(cat, "精度", "%.1f m".format(loc.accuracy)),
            VerifyItem(cat, "Provider", loc.provider ?: "null"),
        ) else listOf(VerifyItem(cat, "状态", "无可用位置"))
    }

    @SuppressLint("MissingPermission")
    private fun readCellInfo(ctx: Context): List<VerifyItem> {
        val cat = "蜂窝网络"
        if (!hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return listOf(VerifyItem(cat, "状态", "缺少定位权限"))
        }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return listOf(VerifyItem(cat, "状态", "TelephonyManager 不可用"))

        val cells = try { tm.allCellInfo } catch (_: Exception) { null }
        if (cells.isNullOrEmpty()) return listOf(VerifyItem(cat, "状态", "无小区信息"))

        val result = mutableListOf<VerifyItem>()
        for ((i, cell) in cells.withIndex()) {
            val prefix = if (cell.isRegistered) "服务" else "邻区#$i"
            result.addAll(parseCellInfo(cat, prefix, cell))
        }
        return result
    }

    private fun parseCellInfo(cat: String, prefix: String, cell: CellInfo): List<VerifyItem> {
        val items = mutableListOf<VerifyItem>()
        when (cell) {
            is CellInfoGsm -> {
                val id = cell.cellIdentity
                items.add(VerifyItem(cat, "$prefix 类型", "GSM"))
                items.add(VerifyItem(cat, "$prefix MCC", id.mccString ?: "null"))
                items.add(VerifyItem(cat, "$prefix MNC", id.mncString ?: "null"))
                items.add(VerifyItem(cat, "$prefix LAC", "${id.lac}"))
                items.add(VerifyItem(cat, "$prefix CID", "${id.cid}"))
                val ss = cell.cellSignalStrength
                items.add(VerifyItem(cat, "$prefix RSSI", "${ss.dbm} dBm"))
            }
            is CellInfoLte -> {
                val id = cell.cellIdentity
                items.add(VerifyItem(cat, "$prefix 类型", "LTE"))
                items.add(VerifyItem(cat, "$prefix MCC", id.mccString ?: "null"))
                items.add(VerifyItem(cat, "$prefix MNC", id.mncString ?: "null"))
                items.add(VerifyItem(cat, "$prefix TAC", "${id.tac}"))
                items.add(VerifyItem(cat, "$prefix CI", "${id.ci}"))
                items.add(VerifyItem(cat, "$prefix PCI", "${id.pci}"))
                items.add(VerifyItem(cat, "$prefix EARFCN", "${id.earfcn}"))
                val ss = cell.cellSignalStrength
                items.add(VerifyItem(cat, "$prefix RSRP", "${ss.rsrp} dBm"))
                items.add(VerifyItem(cat, "$prefix RSRQ", "${ss.rsrq} dB"))
                items.add(VerifyItem(cat, "$prefix RSSI", "${ss.rssi} dBm"))
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                items.add(VerifyItem(cat, "$prefix 类型", "WCDMA"))
                items.add(VerifyItem(cat, "$prefix MCC", id.mccString ?: "null"))
                items.add(VerifyItem(cat, "$prefix MNC", id.mncString ?: "null"))
                items.add(VerifyItem(cat, "$prefix LAC", "${id.lac}"))
                items.add(VerifyItem(cat, "$prefix CID", "${id.cid}"))
                items.add(VerifyItem(cat, "$prefix PSC", "${id.psc}"))
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                    items.add(VerifyItem(cat, "$prefix 类型", "NR/5G"))
                    val id = cell.cellIdentity as? android.telephony.CellIdentityNr
                    if (id != null) {
                        items.add(VerifyItem(cat, "$prefix NCI", "${id.nci}"))
                        items.add(VerifyItem(cat, "$prefix PCI", "${id.pci}"))
                        items.add(VerifyItem(cat, "$prefix NRARFCN", "${id.nrarfcn}"))
                        items.add(VerifyItem(cat, "$prefix TAC", "${id.tac}"))
                    }
                    val ss = cell.cellSignalStrength as? CellSignalStrengthNr
                    if (ss != null) {
                        items.add(VerifyItem(cat, "$prefix SS-RSRP", "${ss.ssRsrp} dBm"))
                        items.add(VerifyItem(cat, "$prefix SS-RSRQ", "${ss.ssRsrq} dB"))
                        items.add(VerifyItem(cat, "$prefix SS-SINR", "${ss.ssSinr} dB"))
                    }
                } else {
                    items.add(VerifyItem(cat, "$prefix 类型", cell.javaClass.simpleName))
                }
            }
        }
        return items
    }

    @SuppressLint("MissingPermission")
    private fun readTelephony(ctx: Context): List<VerifyItem> {
        val cat = "运营商"
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return listOf(VerifyItem(cat, "状态", "不可用"))

        val items = mutableListOf(
            VerifyItem(cat, "运营商名称", tm.networkOperatorName ?: "null"),
            VerifyItem(cat, "运营商代码", tm.networkOperator ?: "null"),
            VerifyItem(cat, "SIM 运营商", tm.simOperator ?: "null"),
            VerifyItem(cat, "SIM 运营商名称", tm.simOperatorName ?: "null"),
            VerifyItem(cat, "SIM 国家", tm.simCountryIso ?: "null"),
            VerifyItem(cat, "网络国家", tm.networkCountryIso ?: "null"),
            VerifyItem(cat, "网络类型", networkTypeName(tm.dataNetworkType)),
            VerifyItem(cat, "漫游", "${tm.isNetworkRoaming}"),
            VerifyItem(cat, "电话类型", phoneTypeName(tm.phoneType)),
        )

        if (hasPermission(ctx, Manifest.permission.READ_PHONE_STATE)) {
            try {
                val ss = tm.serviceState
                if (ss != null) {
                    items.add(VerifyItem(cat, "服务状态", serviceStateName(ss.state)))
                }
            } catch (_: Exception) {}
            items.add(VerifyItem(cat, "数据状态", dataStateName(tm.dataState)))
        }

        return items
    }

    @SuppressLint("MissingPermission")
    private fun readWifi(ctx: Context): List<VerifyItem> {
        val cat = "WiFi"
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return listOf(VerifyItem(cat, "状态", "WifiManager 不可用"))

        if (!wm.isWifiEnabled) return listOf(VerifyItem(cat, "状态", "WiFi 已关闭"))

        val info = wm.connectionInfo ?: return listOf(VerifyItem(cat, "状态", "未连接"))

        val items = mutableListOf(
            VerifyItem(cat, "SSID", info.ssid ?: "null"),
            VerifyItem(cat, "BSSID", info.bssid ?: "null"),
            VerifyItem(cat, "RSSI", "${info.rssi} dBm"),
            VerifyItem(cat, "频率", "${info.frequency} MHz"),
            VerifyItem(cat, "连接速率", "${info.linkSpeed} Mbps"),
            VerifyItem(cat, "MAC", info.macAddress ?: "null"),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            items.add(VerifyItem(cat, "TX 速率", "${info.txLinkSpeedMbps} Mbps"))
            items.add(VerifyItem(cat, "RX 速率", "${info.rxLinkSpeedMbps} Mbps"))
        }

        val dhcp = wm.dhcpInfo
        if (dhcp != null) {
            items.add(VerifyItem(cat, "IP", intToIp(dhcp.ipAddress)))
            items.add(VerifyItem(cat, "网关", intToIp(dhcp.gateway)))
            items.add(VerifyItem(cat, "掩码", intToIp(dhcp.netmask)))
            items.add(VerifyItem(cat, "DNS1", intToIp(dhcp.dns1)))
            items.add(VerifyItem(cat, "DNS2", intToIp(dhcp.dns2)))
        }

        return items
    }

    private fun readNetwork(ctx: Context): List<VerifyItem> {
        val cat = "IP 与连接"
        val items = mutableListOf<VerifyItem>()

        // NetworkInterface
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    val type = if (host.contains(":")) "IPv6" else "IPv4"
                    items.add(VerifyItem(cat, "${ni.name} $type", host))
                }
            }
        } catch (_: Exception) {}

        // LinkProperties
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val net = cm.activeNetwork
            if (net != null) {
                val lp = cm.getLinkProperties(net)
                if (lp != null) {
                    items.add(VerifyItem(cat, "接口", lp.interfaceName ?: "null"))
                    for (dns in lp.dnsServers) {
                        items.add(VerifyItem(cat, "DNS", dns.hostAddress ?: "null"))
                    }
                    for (route in lp.routes) {
                        if (route.isDefaultRoute) {
                            items.add(VerifyItem(cat, "网关", route.gateway?.hostAddress ?: "null"))
                        }
                    }
                }
            }
        }

        return items
    }

    private fun intToIp(ip: Int): String =
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

    private fun networkTypeName(type: Int): String = when (type) {
        0 -> "UNKNOWN"
        1 -> "GPRS"
        2 -> "EDGE"
        3 -> "UMTS"
        13 -> "LTE"
        20 -> "NR"
        else -> "type=$type"
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        0 -> "NONE"
        1 -> "GSM"
        2 -> "CDMA"
        3 -> "SIP"
        else -> "type=$type"
    }

    private fun serviceStateName(state: Int): String = when (state) {
        0 -> "IN_SERVICE"
        1 -> "OUT_OF_SERVICE"
        2 -> "EMERGENCY_ONLY"
        3 -> "POWER_OFF"
        else -> "state=$state"
    }

    private fun dataStateName(state: Int): String = when (state) {
        0 -> "DISCONNECTED"
        1 -> "CONNECTING"
        2 -> "CONNECTED"
        3 -> "SUSPENDED"
        else -> "state=$state"
    }
}
