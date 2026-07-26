package name.caiyao.fakegps.probe

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * Read-back probe: reports what THIS process actually observes through the public Android APIs
 * the module hooks. Emits ONE line of JSON under tag [TAG] so an external test script can assert
 * on it without any manual UI interaction.
 *
 * WHY this exists: verifying spoofing by eyeballing a map/settings screen is neither repeatable
 * nor able to see partial breakage. Two real bugs were invisible that way:
 *   - `Snapshot.hasLteCell()` gates on `ci`, so a profile with only `tac` silently no-ops
 *   - the XSharedPreferences transport only carries SpoofConfig's ~23 fields, while the profile
 *     table has 87 columns, so most cellular/operator fields never reach the hook at all
 *
 * The probe reads via the SAME public APIs a target app would use, so a value here is what a real
 * app would see: spoofed value => hook works end-to-end; real device value => chain is broken.
 */
object HookProbe {

    const val TAG = "FakeGPSProbe"

    /** Collect every observable field and log it as a single JSON line. */
    @SuppressLint("MissingPermission")
    fun run(context: Context) {
        val out = JSONObject()

        // ---- LOCATION ----------------------------------------------------------------
        val loc = JSONObject()
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = gps ?: net
            loc.put("latitude", best?.latitude)
            loc.put("longitude", best?.longitude)
            loc.put("altitude", best?.altitude)
            loc.put("accuracy", best?.accuracy)
            // isMock is a detection surface: a high-fidelity spoof must keep this false.
            loc.put("isMock", best?.isMock)
            loc.put("provider", best?.provider)
        } catch (t: Throwable) {
            loc.put("error", t.toString())
        }
        out.put("location", loc)

        // ---- CELLULAR (from getAllCellInfo) ------------------------------------------
        val lte = JSONObject()
        val gsm = JSONObject()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val all = tm.allCellInfo.orEmpty()
            out.put("cellInfoCount", all.size)
            for (info in all) {
                when (info) {
                    is CellInfoLte -> if (lte.length() == 0) {
                        val id = info.cellIdentity
                        val ss = info.cellSignalStrength
                        lte.put("tac", id.tac)
                        lte.put("ci", id.ci)
                        lte.put("pci", id.pci)
                        lte.put("earfcn", id.earfcn)
                        lte.put("mccString", id.mccString)
                        lte.put("mncString", id.mncString)
                        lte.put("rsrp", ss.rsrp)
                        lte.put("rsrq", ss.rsrq)
                    }
                    is CellInfoGsm -> if (gsm.length() == 0) {
                        val id = info.cellIdentity
                        gsm.put("lac", id.lac)
                        gsm.put("cid", id.cid)
                        gsm.put("mccString", id.mccString)
                        gsm.put("mncString", id.mncString)
                    }
                }
            }
        } catch (t: Throwable) {
            lte.put("error", t.toString())
        }
        out.put("lte", lte)
        out.put("gsm", gsm)

        // ---- OPERATOR / NETWORK ------------------------------------------------------
        val op = JSONObject()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            op.put("networkOperatorName", tm.networkOperatorName)
            op.put("networkOperator", tm.networkOperator)
            op.put("simOperator", tm.simOperator)
            op.put("simOperatorName", tm.simOperatorName)
            op.put("simCountryIso", tm.simCountryIso)
            op.put("networkCountryIso", tm.networkCountryIso)
            op.put("isNetworkRoaming", tm.isNetworkRoaming)
            op.put("phoneType", tm.phoneType)
            @Suppress("DEPRECATION")
            op.put("networkType", tm.networkType)
        } catch (t: Throwable) {
            op.put("error", t.toString())
        }
        out.put("operator", op)

        // ---- WIFI --------------------------------------------------------------------
        val wifi = JSONObject()
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            @Suppress("DEPRECATION")
            wifi.put("ssid", info?.ssid)
            @Suppress("DEPRECATION")
            wifi.put("bssid", info?.bssid)
            @Suppress("DEPRECATION")
            wifi.put("rssi", info?.rssi)
            @Suppress("DEPRECATION")
            wifi.put("frequency", info?.frequency)
        } catch (t: Throwable) {
            wifi.put("error", t.toString())
        }
        out.put("wifi", wifi)

        Log.w(TAG, out.toString())
    }
}
