package name.caiyao.fakegps.config

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import name.caiyao.fakegps.data.ProviderAuthority
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.SpoofSettings

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

    /** Wall-clock time of the last publish. Read by the UI only; the hook ignores it. */
    const val KEY_PUBLISHED_AT = "published_at"
    const val KEY_PUBLISH_FAILED = "publish_failed"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    /**
     * Private (app-only) store for the durable publication outcome. The hook reads only [KEY_JSON]
     * from the transported prefs, so this metadata never needs to be world-readable — and, crucially,
     * writing it here never resets the payload file's cross-process-readable mode and is written
     * fail-closed so a process death cannot leave a success marker beside an unverified payload.
     */
    private const val PUBLISH_STATE_PREFS = "publish_state"
    private const val KEY_STATE_INITIALIZED = "state_initialized"

    /**
     * Transport payload version. Bumped from SpoofConfig's v1 typed schema to the flat field map.
     * The hook rejects a payload it cannot interpret rather than silently mis-reading it, and keeps
     * its last-known-good config instead of reverting to real device data mid-test.
     */
    const val SCHEMA_VERSION = 4
    /** Losslessly readable predecessor: same flat fields/unavailable shape, without delivery mode. */
    const val PREVIOUS_SCHEMA_VERSION = 3
    /** Losslessly readable predecessor: it has the same flat `fields` map and no unavailable set. */
    const val LEGACY_SCHEMA_VERSION = 2


    private val APP_URI: Uri = Uri.parse("content://${ProviderAuthority.AUTHORITY}/app")
    private val SETTINGS_URI: Uri = Uri.parse("content://${ProviderAuthority.AUTHORITY}/settings")

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
    @JvmOverloads
    fun sync(
        context: Context,
        profileId: Long? = null,
        clearIfMissing: Boolean = false,
    ): Boolean {
        Log.w(TAG, "sync() ENTER")
        return try {
            val prior = readPublishState(context)
            val requestedProfileId = profileId ?: prior.activeProfileId
            val built = buildFieldMapJson(context, requestedProfileId)
            if (ConfigPublicationContract.shouldKeepLastGoodPayload(
                    requestedProfileId = requestedProfileId,
                    resolvedProfileId = built.profileId,
                    clearIfMissing = clearIfMissing,
                )
            ) {
                Log.w(
                    TAG,
                    "profileId=$requestedProfileId temporarily unavailable; keeping last-good payload",
                )
                markPublicationFailure(context)
                return false
            }
            val jsonStr = built.json

            // MODE_WORLD_READABLE throws SecurityException on Android N+ unless the Xposed
            // framework suppresses it (Vector hooks checkMode for this). A suppressed (non-throwing)
            // call does NOT imply a world-readable file: on N+ that mode no longer sets the
            // other-read bit and the Vector mirror is written 0660, so this flag means only "the
            // cross-process transport was accepted". Actual readability is ensured + verified after
            // commit (below). Fall back to a private write so we can tell the failure modes apart.
            var modeWorldReadableAccepted = true
            @Suppress("DEPRECATION")
            val prefs = try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (se: Throwable) {
                modeWorldReadableAccepted = false
                Log.e(TAG, "MODE_WORLD_READABLE rejected (${se.javaClass.simpleName}) — falling back to MODE_PRIVATE", se)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            // Durable publication OUTCOME lives in a SEPARATE private store (see PublishState). Two
            // reasons it is NOT in the payload file: the transported payload must stay world-readable,
            // so a second write to it — to record a timestamp — would reset that mode; and a success
            // marker written INTO the payload commit survives a process death that happens before
            // readability is verified, resurrecting the false-green. So the outcome is written
            // FAIL-CLOSED: marked failed BEFORE the payload commit, flipped to success ONLY after the
            // committed file is verified other-readable. An interruption at any point therefore leaves
            // a failure marker (never a live timestamp) and never advances the active pointer past the
            // last verified-good profile.
            writePublishState(context, ConfigPublicationContract.preCommitFailClosed(prior))

            val committed = prefs.edit().putString(KEY_JSON, jsonStr).commit()
            // "MODE_WORLD_READABLE did not throw" is NOT proof the hook can read the file: on N+ that
            // mode no longer sets the other-read bit and the Vector mirror is written 0660. Make our
            // own committed file other-readable and verify the bit actually landed; publication counts
            // only when the target UID can really read it.
            val crossProcessReadable =
                modeWorldReadableAccepted && committed && ensureOtherReadable(prefs)
            val published = ConfigPublicationContract.isCrossProcessPublishSuccessful(
                crossProcessReadable,
                committed,
            )
            val outcome = if (published) {
                ConfigPublicationContract.onVerifiedPublish(System.currentTimeMillis(), built.profileId)
            } else {
                ConfigPublicationContract.onVerifiedFailure(prior)
            }
            writePublishState(context, outcome)

            Log.w(
                TAG,
                "published crossProcess=$published readable=$crossProcessReadable " +
                    "transportAccepted=$modeWorldReadableAccepted commit=$committed " +
                    "profileId=${built.profileId} fp=${fingerprint(jsonStr)} bytes=${jsonStr.length}",
            )
            published
        } catch (e: Throwable) {
            Log.e(TAG, "sync failed", e)
            markPublicationFailure(context)
            false
        }
    }

    /**
     * Serialize the explicitly selected profile as `{schemaVersion, mode, activeHours, fields:{…}}`.
     *
     * `fields` is produced by walking EVERY cursor column — no per-field code — so the payload
     * always mirrors the profile table and new columns need no change here. Column values keep
     * their SQLite type so the hook side reads them back with matching types.
     */
    private data class BuiltPayload(val json: String, val profileId: Long?)

    private fun buildFieldMapJson(context: Context, profileId: Long?): BuiltPayload {
        // Force Room to open and run pending migrations before the provider creates its second,
        // read-only connection. Without this, the first publish after an upgrade can observe the
        // previous schema and silently omit newly migrated metadata.
        AppDatabase.getInstance(context).openHelper.readableDatabase
        val cr = context.contentResolver
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put(
            "refreshIntervalSec",
            SpoofSettings.getInstance(context).readRefreshIntervalSec(),
        )
        root.put(
            "locationDeliveryMode",
            SpoofSettings.getInstance(context).readLocationDeliveryMode().wireValue,
        )

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

        // profile row -> flat field map plus an orthogonal explicit-unavailable set.
        val fields = JSONObject()
        var storedUnavailable: String? = null
        var resolvedProfileId: Long? = null
        val profileCursor = if (profileId == null) {
            cr.query(APP_URI, null, null, null, "id ASC")
        } else {
            cr.query(APP_URI, null, "id = ?", arrayOf(profileId.toString()), null)
        }
            ?: throw IllegalStateException("profile query failed")
        profileCursor.use { c ->
            if (c.moveToFirst()) {
                resolvedProfileId = c.longOrNull("id")
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    if (name == "unavailable_fields") {
                        storedUnavailable = if (c.isNull(i)) null else c.getString(i)
                        continue
                    }
                    if (c.isNull(i)) continue                 // NULL = passthrough: never transported
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
        val fieldNames = buildSet {
            val keys = fields.keys()
            while (keys.hasNext()) add(keys.next())
        }
        val requested = UnavailableFieldSet.decode(storedUnavailable).toList()
        val unavailable = UnavailablePayloadContract.validate(fieldNames, requested)
        root.put("fields", fields)
        root.put("unavailable", JSONArray(unavailable.asList()))
        Log.w(
            TAG,
            "field map built: ${fields.length()} spoof fields, " +
                "${unavailable.asList().size} unavailable fields",
        )
        return BuiltPayload(root.toString(), resolvedProfileId)
    }

    /**
     * Read back the exact payload the hook consumes.
     *
     * Returns null when nothing has ever been published. The file is written MODE_WORLD_READABLE for
     * other processes; reading it from our own process needs no special mode.
     *
     * The verify UI reconciles against THIS rather than the DB row on purpose — a DB read proves
     * only that the editor saved something, while the defect that actually shipped lived in the gap
     * between the DB and this payload.
     */
    @JvmStatic
    fun readPublished(context: Context): PayloadRead = try {
        val text = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (text == null) PayloadRead.Absent else PayloadRead.Raw(text)
    } catch (t: Throwable) {
        // Distinct from Absent on purpose: a failed read means the hook is still running its
        // last-known-good config, which is the opposite of "nothing is being spoofed".
        PayloadRead.ReadError("${t.javaClass.simpleName}: ${t.message}")
    }

    /** Wall-clock time of the last VERIFIED publish, or null if never published / not recorded. */
    @JvmStatic
    fun readPublishedAt(context: Context): Long? = readPublishState(context).publishedAtMs

    @JvmStatic
    fun hasPublicationFailure(context: Context): Boolean = readPublishState(context).publishFailed

    /**
     * The durable publication outcome. A never-written store migrates the active pointer once from
     * the legacy transported prefs; a failed READ fails closed (treated as a publication failure) so
     * the UI never softens a real failure into "pending".
     */
    private fun readPublishState(context: Context): ConfigPublicationContract.PublishState =
        runCatching {
            val s = context.getSharedPreferences(PUBLISH_STATE_PREFS, Context.MODE_PRIVATE)
            if (!s.getBoolean(KEY_STATE_INITIALIZED, false)) {
                ConfigPublicationContract.PublishState(
                    publishedAtMs = null,
                    publishFailed = false,
                    activeProfileId = legacyActiveProfileId(context),
                )
            } else {
                ConfigPublicationContract.PublishState(
                    publishedAtMs = s.getLong(KEY_PUBLISHED_AT, 0L).takeIf { it > 0L },
                    publishFailed = s.getBoolean(KEY_PUBLISH_FAILED, false),
                    activeProfileId = s.getLong(KEY_ACTIVE_PROFILE_ID, 0L).takeIf { it > 0L },
                )
            }
        }.getOrElse {
            Log.e(TAG, "could not read publish state; failing closed", it)
            ConfigPublicationContract.PublishState(null, publishFailed = true, activeProfileId = null)
        }

    private fun writePublishState(context: Context, state: ConfigPublicationContract.PublishState) {
        runCatching {
            val e = context.getSharedPreferences(PUBLISH_STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STATE_INITIALIZED, true)
                .putBoolean(KEY_PUBLISH_FAILED, state.publishFailed)
            if (state.publishedAtMs != null) {
                e.putLong(KEY_PUBLISHED_AT, state.publishedAtMs)
            } else {
                e.remove(KEY_PUBLISHED_AT)
            }
            if (state.activeProfileId != null) {
                e.putLong(KEY_ACTIVE_PROFILE_ID, state.activeProfileId)
            } else {
                e.remove(KEY_ACTIVE_PROFILE_ID)
            }
            e.commit()
        }.onFailure { Log.e(TAG, "could not persist publish state", it) }
    }

    /** Mark the current publication failed while preserving the last verified-good active pointer. */
    private fun markPublicationFailure(context: Context) {
        writePublishState(context, ConfigPublicationContract.onVerifiedFailure(readPublishState(context)))
    }

    /**
     * Grant other-read on our just-committed prefs file (the hook runs as the TARGET app's UID) and
     * confirm the bit actually landed. MODE_WORLD_READABLE no longer applies it on Android N+ and
     * the Vector mirror is written 0660, so this — not "the mode call did not throw" — is what makes
     * a publish actually reachable. Returns false (a real publication failure) when the backing file
     * cannot be located or made other-readable.
     */
    private fun ensureOtherReadable(prefs: SharedPreferences): Boolean =
        try {
            val file = sharedPrefsFileOrNull(prefs)
            if (file == null) {
                Log.e(TAG, "cannot resolve prefs file to verify cross-process readability")
                false
            } else {
                // We own the file, so we can widen its mode; the commit's atomic rename resets it to
                // the (non-world-readable) default, hence doing it here, right after commit.
                file.setReadable(true, /* ownerOnly = */ false)
                ConfigPublicationContract.isOtherReadable(Os.stat(file.path).st_mode)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cross-process readability could not be ensured", t)
            false
        }

    /** The concrete file behind a [SharedPreferences] (also the Vector mirror when redirected). */
    private fun sharedPrefsFileOrNull(prefs: SharedPreferences): File? =
        try {
            prefs.javaClass.getDeclaredField("mFile")
                .apply { isAccessible = true }
                .get(prefs) as? File
        } catch (t: Throwable) {
            Log.e(TAG, "reflective prefs file lookup failed", t)
            null
        }

    /** SHA-256 of the published payload — config provenance, comparable across UI / log / probe. */
    private fun fingerprint(json: String): String = PublishedConfig.fingerprint(json)

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

    private fun Cursor.longOrNull(col: String): Long? {
        val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getLong(i) else null
    }

    /**
     * Legacy migration read: the active pointer as stored in the OLD transported prefs, before the
     * publication outcome moved to its own private store. Consulted only until the new store is
     * initialized, so an upgrade keeps its active profile.
     */
    private fun legacyActiveProfileId(context: Context): Long? = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_ACTIVE_PROFILE_ID, 0L)
            .takeIf { it > 0L }
    }.getOrNull()
}
