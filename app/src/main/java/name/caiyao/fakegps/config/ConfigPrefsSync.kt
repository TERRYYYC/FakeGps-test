package name.caiyao.fakegps.config

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * WRITE side of the XSharedPreferences config transport.
 *
 * Publishes the current effective [SpoofConfig] (first profile + settings) into a
 * WORLD-READABLE SharedPreferences file so the Xposed hook — running INSIDE the target app's
 * process (e.g. Google Maps) — can read it via [de.robv.android.xposed.XSharedPreferences].
 *
 * WHY this replaces the ContentProvider path:
 *   Android 11+ package-visibility filtering means a target app that doesn't declare <queries>
 *   for us cannot even resolve our exported provider — logcat shows
 *   "Failed to find provider info for name.caiyao.fakegps.data.AppInfoProvider" in the Maps
 *   process, so the cross-process query returns null and the hook only ever sees passthrough.
 *   XSharedPreferences reads a file directly (Vector redirects MODE_WORLD_READABLE prefs into a
 *   permissive-SELinux safe-zone), bypassing package visibility entirely.
 *
 * Reuses Fable's canonical config classes: [SpoofConfig] + [ConfigCodec]. The READ side is
 * [SpoofConfigMapper] (JSON→Snapshot) + [ConfigHolder] (last-known-good).
 *
 * NOTE: this in-process read of the ContentProvider is fine — the visibility problem only
 * affects OTHER apps' processes, not our own.
 */
object ConfigPrefsSync {
    private const val TAG = "ConfigPrefsSync"
    const val PREFS_NAME = "spoof_config"
    const val KEY_JSON = "json"

    /**
     * Transport payload version. Bumped from SpoofConfig's v1 typed schema to the flat field map.
     * The hook rejects a payload it cannot interpret rather than silently mis-reading it, and keeps
     * its last-known-good config instead of reverting to real device data mid-test.
     */
    const val SCHEMA_VERSION = 2

    private val APP_URI: Uri = Uri.parse("content://name.caiyao.fakegps.data.AppInfoProvider/app")
    private val SETTINGS_URI: Uri = Uri.parse("content://name.caiyao.fakegps.data.AppInfoProvider/settings")

    /**
     * Publish the effective profile as a FLAT field map mirroring the profile table.
     *
     * Every non-null column is carried verbatim — no per-field code, so a new DB column reaches the
     * hook automatically. This replaces routing through the typed [SpoofConfig], which declared
     * only 23 of the table's 87 columns and silently dropped mcc/mnc/lac/cid/operator_name.
     * The hook rebuilds a Snapshot from this map via the same field list it uses for cursors.
     *
     * Invariants preserved: only non-null values are written (NULL = passthrough), the payload
     * carries [SCHEMA_VERSION] so the reader can reject an incompatible build, and a content
     * fingerprint is emitted so config provenance stays verifiable across UI / log / probe.
     */
    @JvmStatic
    fun sync(context: Context) {
        Log.w(TAG, "sync() ENTER")
        try {
            val jsonStr = buildFieldMapJson(context)

            // MODE_WORLD_READABLE throws SecurityException on Android N+ unless the Xposed
            // framework suppresses it (Vector hooks checkMode for this). Fall back to a private
            // write so we can tell the two failure modes apart in the log.
            @Suppress("DEPRECATION")
            val prefs = try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (se: Throwable) {
                Log.e(TAG, "MODE_WORLD_READABLE rejected (${se.javaClass.simpleName}) — falling back to MODE_PRIVATE", se)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            val ok = prefs.edit().putString(KEY_JSON, jsonStr).commit()
            Log.w(TAG, "published commit=$ok fp=${fingerprint(jsonStr)} bytes=${jsonStr.length}")
        } catch (e: Throwable) {
            Log.e(TAG, "sync failed", e)
        }
    }

    /**
     * Serialize the effective profile (first row) as `{schemaVersion, mode, activeHours, fields:{…}}`.
     *
     * `fields` is produced by walking EVERY cursor column — no per-field code — so the payload
     * always mirrors the profile table and new columns need no change here. Column values keep
     * their SQLite type so the hook side reads them back with matching types.
     */
    private fun buildFieldMapJson(context: Context): String {
        val cr = context.contentResolver
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)

        // settings (mode / active hours) — small, fixed shape
        var mode = "always_on"
        cr.query(SETTINGS_URI, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.strOrNull("spoof_mode")?.let { mode = it }
                val s = c.intOrNull("active_hour_start")
                val e = c.intOrNull("active_hour_end")
                if (s != null && e != null) {
                    root.put("activeHours", JSONObject().put("start", s).put("end", e))
                }
            }
        }
        root.put("mode", mode)

        // profile row -> flat field map (generic: every non-null column, whatever it is)
        val fields = JSONObject()
        cr.query(APP_URI, null, null, null, "id ASC")?.use { c ->
            if (c.moveToFirst()) {
                for (i in 0 until c.columnCount) {
                    if (c.isNull(i)) continue                 // NULL = passthrough: never transported
                    val name = c.getColumnName(i)
                    if (name == "id") continue
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> fields.put(name, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT   -> fields.put(name, c.getDouble(i))
                        Cursor.FIELD_TYPE_STRING  -> fields.put(name, c.getString(i))
                        else -> { /* BLOB/unknown: not a spoofable scalar, skip */ }
                    }
                }
            }
        }
        root.put("fields", fields)
        Log.w(TAG, "field map built: ${fields.length()} non-null fields")
        return root.toString()
    }

    /** SHA-256 of the published payload — config provenance, comparable across UI / log / probe. */
    private fun fingerprint(json: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        return "sha256:" + d.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun Cursor.strOrNull(col: String): String? {
        val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getString(i) else null
    }
    private fun Cursor.dblOrNull(col: String): Double? {
        val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getDouble(i) else null
    }
    private fun Cursor.fltOrNull(col: String): Float? {
        val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getFloat(i) else null
    }
    private fun Cursor.intOrNull(col: String): Int? {
        val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getInt(i) else null
    }
}
