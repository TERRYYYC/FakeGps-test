package name.caiyao.fakegps.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import name.caiyao.fakegps.config.ConfigCodec;
import name.caiyao.fakegps.config.SpoofConfig;

/**
 * Xposed module entry point.
 *
 * Architecture:
 *   1. Register ALL hooks exactly ONCE in handleLoadPackage()
 *   2. Hooks read from a shared AtomicReference<Snapshot> at invocation time
 *   3. A background timer refreshes the Snapshot (coordinates + config)
 *      WITHOUT re-registering hooks
 *
 * Config transport = XSharedPreferences (world-readable prefs file), NOT the exported
 * ContentProvider. WHY: Android 11+ package-visibility filtering means a target app that
 * doesn't declare <queries> for us cannot even resolve our provider ("Failed to find provider
 * info"), so cross-process queries return null. XSharedPreferences reads a file directly
 * (Vector redirects MODE_WORLD_READABLE prefs into a permissive-SELinux safe-zone), bypassing
 * package visibility. WRITE side = {@code name.caiyao.fakegps.config.ConfigPrefsSync}.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeGPS";

    /** Must match ConfigPrefsSync.PREFS_NAME / KEY_JSON / SCHEMA_VERSION on the app write side. */
    private static final String PREFS_NAME = "spoof_config";
    private static final String PREFS_KEY_JSON = "json";
    private static final int TRANSPORT_SCHEMA_VERSION = 2;

    /** Current spoofing config. Hooks read this atomically via CURRENT.get(). */
    static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.PASSTHROUGH);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // PROBE MODE (step 0 of the Maps investigation): our own app is intentionally hooked so it
        // can act as a CONTROLLED probe. It uses plain LocationManager.getLastKnownLocation
        // (MapScreen/VerifyViewModel) with no GMS fused path, so it isolates two variables:
        //   - our app shows the spoofed location  => client-side hooking works; the remaining gap is
        //     purely Google Maps' cross-process GMS fused path (uid 10213 computes it, not us)
        //   - our app still shows the real location => something more basic is broken, and chasing
        //     Maps' fused path would be premature
        // Revert to skipping self once the probe verdict is recorded.
        if (false && "name.caiyao.fakegps".equals(lpparam.packageName)) {
            return;
        }

        // 1. Load initial config (XSharedPreferences works here — it's a file read, no app context needed)
        Snapshot initial = loadSnapshot();
        CURRENT.set(initial);
        XposedBridge.log(TAG + ": Loaded config for " + lpparam.packageName
                + " | location=" + initial.hasLocation()
                + " | cell=" + initial.hasGsmCell()
                + " | lte=" + initial.hasLteCell());

        // 2. Register hooks ONCE — they read CURRENT.get() at invocation time
        HookUtils.registerAllHooks(lpparam.classLoader);

        // 3. Background refresh: re-read prefs periodically, do NOT re-register hooks
        final Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    Snapshot refreshed = loadSnapshot();
                    CURRENT.set(refreshed);
                    XposedBridge.log(TAG + ": [DIAG] timer refresh -> hasLocation=" + refreshed.hasLocation());
                }
                sendEmptyMessageDelayed(1, 30 * 1000);
            }
        };
        handler.sendEmptyMessageDelayed(1, 3 * 1000);
    }

    /**
     * Load the effective spoof config from the world-readable prefs snapshot the app publishes
     * (see ConfigPrefsSync). Parses via Fable's canonical {@link ConfigCodec} → {@link SpoofConfig}
     * and maps to a hook-layer {@link Snapshot} via {@link SpoofConfigMapper}.
     *
     * NULL/absent field == passthrough (real device value). On read/parse failure we keep the
     * last-known-good CURRENT rather than reverting to real data mid-test.
     */
    private Snapshot loadSnapshot() {
        try {
            XSharedPreferences prefs = new XSharedPreferences("name.caiyao.fakegps", PREFS_NAME);
            prefs.makeWorldReadable();
            prefs.reload();

            String jsonStr = prefs.getString(PREFS_KEY_JSON, null);
            if (jsonStr == null) {
                // last-known-good (review FC-2): a MISSING payload on a refresh tick is not the
                // same as "no config". The prefs file can be transiently unreadable (mid-write,
                // permission flap), and reverting an ACTIVE spoof to real device data would leak
                // the true environment to the app under test. Only pass through when nothing has
                // ever been published (first launch), where real values are the safe default.
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                XposedBridge.log(TAG + ": [DIAG] no prefs json (canRead=" + prefs.getFile().canRead()
                        + " path=" + prefs.getFile().getPath() + ") -> "
                        + (resolved == Snapshot.PASSTHROUGH ? "passthrough (never published)"
                                                            : "keep last-known-good"));
                return resolved;
            }

            org.json.JSONObject root = new org.json.JSONObject(jsonStr);

            // schemaVersion gate: refuse to interpret a payload written by an incompatible build
            // rather than silently mis-reading it. Keep last-known-good (never revert to real data
            // mid-test) instead of falling back to passthrough.
            int version = root.optInt("schemaVersion", -1);
            if (version != TRANSPORT_SCHEMA_VERSION) {
                XposedBridge.log(TAG + ": [DIAG] incompatible schemaVersion=" + version
                        + " (expected " + TRANSPORT_SCHEMA_VERSION + ") -> keep last-known-good");
                return CURRENT.get();
            }

            String mode = root.optString("mode", "always_on");
            if ("off".equals(mode)) {
                XposedBridge.log(TAG + ": [DIAG] mode=off -> passthrough");
                return Snapshot.PASSTHROUGH;
            }
            if ("time_based".equals(mode)) {
                org.json.JSONObject hours = root.optJSONObject("activeHours");
                if (hours != null) {
                    int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                    int start = hours.optInt("start", 0);
                    int end = hours.optInt("end", 24);
                    boolean inRange = (start <= end) ? (h >= start && h < end) : (h >= start || h < end);
                    if (!inRange) {
                        XposedBridge.log(TAG + ": [DIAG] outside active hours (" + start + "-" + end
                                + ") current=" + h + " -> passthrough");
                        return Snapshot.PASSTHROUGH;
                    }
                }
            }

            // Flat field map -> Snapshot via the SAME field list used for DB cursors, so transport
            // coverage equals the profile table instead of a hand-maintained subset.
            org.json.JSONObject fields = root.optJSONObject("fields");
            if (fields == null) {
                // last-known-good (review FC-2): a v2-valid payload that carries no `fields` object
                // is structurally incomplete, not an instruction to stop spoofing. Publishing an
                // all-null Snapshot here would silently drop an active spoof back to real data.
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                XposedBridge.log(TAG + ": [DIAG] payload has no 'fields' -> "
                        + (resolved == Snapshot.PASSTHROUGH ? "passthrough (never published)"
                                                            : "keep last-known-good"));
                return resolved;
            }
            Snapshot s = Snapshot.fromJson(fields);
            XposedBridge.log(TAG + ": [DIAG] prefs loaded fields=" + (fields == null ? 0 : fields.length())
                    + " hasLocation=" + s.hasLocation() + " lat=" + s.latitude + " lng=" + s.longitude
                    + " hasLte=" + s.hasLteCell() + " hasGsm=" + s.hasGsmCell());
            return s;
        } catch (Throwable t) {
            // Read/parse failure: keep last-known-good (do NOT revert to real device data mid-test).
            XposedBridge.log(TAG + ": [DIAG] loadSnapshot prefs error: " + t);
            return CURRENT.get();
        }
    }
}
