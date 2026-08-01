package name.caiyao.fakegps.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import name.caiyao.fakegps.config.ConfigCodec;
import name.caiyao.fakegps.config.SpoofConfig;
import name.caiyao.fakegps.config.ConfigPrefsSync;
import name.caiyao.fakegps.config.PublishedConfig;
import name.caiyao.fakegps.config.TransportSchemaContract;
import name.caiyao.fakegps.verify.RuntimeSelfHookPolicy;
import name.caiyao.fakegps.verify.RuntimeHookSentinel;
import name.caiyao.fakegps.verify.RuntimeEvidence;

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

    private static final String PREFS_NAME = ConfigPrefsSync.PREFS_NAME;
    private static final String PREFS_KEY_JSON = ConfigPrefsSync.KEY_JSON;
    private static final int TRANSPORT_SCHEMA_VERSION = ConfigPrefsSync.SCHEMA_VERSION;
    private static final int LEGACY_TRANSPORT_SCHEMA_VERSION = ConfigPrefsSync.LEGACY_SCHEMA_VERSION;

    /**
     * Verbose diagnostics, debug builds only. These run inside the TARGET app's process, so in a
     * release build they would both add noise and advertise the module's presence in logcat.
     */
    private static void debug(String msg) {
        if (name.caiyao.fakegps.BuildConfig.DEBUG) {
            XposedBridge.log(TAG + ": [DIAG] " + msg);
        }
    }

    /** Current spoofing config. Hooks read this atomically via CURRENT.get(). */
    static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.PASSTHROUGH);

    /** One owner per module classloader/process, even if LSPosed repeats the callback. */
    private static final HookRuntimeOwnership RUNTIME_OWNERSHIP = new HookRuntimeOwnership();
    private static final HookRefreshScheduler REFRESH_SCHEDULER = new HookRefreshScheduler();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // The configuration process is never self-hooked in release. The sole shipped exception
        // is the private one-shot :hook_verify process, selected by the shared policy below.
        //
        // A debug build hooks its own process so it can act as a controlled probe: it reads plain
        // LocationManager (MapScreen/VerifyViewModel, no GMS fused path), which is what lets
        // scripts/test-hook.sh tell "the hook machinery is broken" apart from "the target app uses
        // an API we don't cover". A release build must never spoof its own UI — that would make the
        // configuration screen display the fake values back to the user as if they were real. The
        // release probe keeps that isolation while providing a deliberately hook-enabled reader.
        if (!RuntimeSelfHookPolicy.shouldHook(
                name.caiyao.fakegps.BuildConfig.DEBUG,
                lpparam.packageName,
                lpparam.processName)) {
            return;
        }

        // 1. Load initial config (XSharedPreferences works here — it's a file read, no app context needed)
        Snapshot initial = loadSnapshot();
        CURRENT.set(initial);
        XposedBridge.log(TAG + ": Loaded config for " + lpparam.packageName
                + " | location=" + initial.hasLocation()
                + " | cellRebuild=" + initial.hasCellReconstructionDecision());

        // 2. Register once per target classloader. LSPosed may repeat handleLoadPackage in the
        // same process; without this gate every getter receives duplicate hooks.
        ClassLoader targetClassLoader = lpparam.classLoader != null
                ? lpparam.classLoader
                : MainHook.class.getClassLoader();
        if (RUNTIME_OWNERSHIP.claimHooks(targetClassLoader)) {
            installProbeSentinelIfPresent(targetClassLoader);
            HookUtils.registerAllHooks(targetClassLoader);
        }

        // 3. One background refresh per process. Duplicate callbacks must not multiply timers.
        if (!RUNTIME_OWNERSHIP.claimScheduler()) {
            debug("duplicate load-package callback; refresh scheduler already owned");
            return;
        }
        XposedBridge.log(RuntimeEvidence.schedulerOwned(
                lpparam.processName, REFRESH_SCHEDULER.currentDelayMs()));
        final Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    long previousDelayMs = REFRESH_SCHEDULER.currentDelayMs();
                    Snapshot refreshed = loadSnapshot();
                    CURRENT.set(refreshed);
                    long nextDelayMs = REFRESH_SCHEDULER.currentDelayMs();
                    if (previousDelayMs != nextDelayMs) {
                        XposedBridge.log(RuntimeEvidence.intervalChanged(
                                lpparam.processName, previousDelayMs, nextDelayMs));
                    }
                    debug("timer refresh -> hasLocation=" + refreshed.hasLocation());
                }
                sendEmptyMessageDelayed(1, REFRESH_SCHEDULER.currentDelayMs());
            }
        };
        handler.sendEmptyMessageDelayed(1, HookRefreshScheduler.INITIAL_DELAY_MS);
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
                debug("no prefs json (canRead=" + prefs.getFile().canRead()
                        + " path=" + prefs.getFile().getPath() + ") -> "
                        + (resolved == Snapshot.PASSTHROUGH ? "passthrough (never published)"
                                                            : "keep last-known-good"));
                return resolved;
            }

            org.json.JSONObject root = new org.json.JSONObject(jsonStr);

            Integer refreshIntervalSec = null;
            Object rawRefreshInterval = root.opt("refreshIntervalSec");
            if (rawRefreshInterval instanceof Number) {
                refreshIntervalSec = ((Number) rawRefreshInterval).intValue();
            }

            // schemaVersion gate: refuse to interpret a payload written by an incompatible build
            // rather than silently mis-reading it. Keep last-known-good (never revert to real data
            // mid-test) instead of falling back to passthrough.
            int version = root.optInt("schemaVersion", -1);
            String fingerprint = PublishedConfig.Companion.fingerprint(jsonStr);
            boolean legacyV2 = version == LEGACY_TRANSPORT_SCHEMA_VERSION;
            if (!TransportSchemaContract.supports(version)) {
                XposedBridge.log(TAG + ": transport rejected schema=" + version
                        + " expected=" + TRANSPORT_SCHEMA_VERSION + " fp=" + fingerprint);
                return CURRENT.get();
            }
            XposedBridge.log(TAG + ": transport accepted schema=" + version
                    + " fp=" + fingerprint);

            String mode = root.optString("mode", "always_on");
            if ("off".equals(mode)) {
                debug("mode=off -> passthrough");
                return acceptLoadedSnapshot(Snapshot.PASSTHROUGH, refreshIntervalSec);
            }
            if ("time_based".equals(mode)) {
                org.json.JSONObject hours = root.optJSONObject("activeHours");
                if (hours != null) {
                    int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                    int start = hours.optInt("start", 0);
                    int end = hours.optInt("end", 24);
                    boolean inRange = (start <= end) ? (h >= start && h < end) : (h >= start || h < end);
                    if (!inRange) {
                        debug("outside active hours (" + start + "-" + end
                                + ") current=" + h + " -> passthrough");
                        return acceptLoadedSnapshot(Snapshot.PASSTHROUGH, refreshIntervalSec);
                    }
                }
            }

            // Flat field map -> Snapshot via the SAME field list used for DB cursors, so transport
            // coverage equals the profile table instead of a hand-maintained subset.
            org.json.JSONObject fields = root.optJSONObject("fields");
            if (fields == null) {
                // last-known-good (review FC-2): a v3-valid payload that carries no `fields` object
                // is structurally incomplete, not an instruction to stop spoofing. Publishing an
                // all-null Snapshot here would silently drop an active spoof back to real data.
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                debug("payload has no 'fields' -> "
                        + (resolved == Snapshot.PASSTHROUGH ? "passthrough (never published)"
                                                            : "keep last-known-good"));
                return resolved;
            }
            org.json.JSONArray unavailableJson = root.optJSONArray("unavailable");
            if (unavailableJson == null && !legacyV2) {
                Snapshot resolved = Snapshot.keepLastKnownGoodOr(CURRENT.get());
                debug("payload has no 'unavailable' array -> keep last-known-good");
                return resolved;
            }
            java.util.List<String> requestedUnavailable = new java.util.ArrayList<>();
            if (unavailableJson != null) {
                for (int i = 0; i < unavailableJson.length(); i++) {
                    Object value = unavailableJson.get(i);
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException("unavailable entry is not a string");
                    }
                    requestedUnavailable.add((String) value);
                }
            }
            java.util.Set<String> configuredFields = new java.util.HashSet<>();
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) configuredFields.add(keys.next());
            name.caiyao.fakegps.config.UnavailablePayloadContract.Validated unavailable =
                    name.caiyao.fakegps.config.UnavailablePayloadContract.validate(
                            configuredFields, requestedUnavailable);
            Snapshot s = Snapshot.fromJson(fields, unavailable.asSet());
            debug("prefs loaded fields=" + (fields == null ? 0 : fields.length())
                    + " unavailable=" + unavailable.asList().size()
                    + " hasLocation=" + s.hasLocation() + " lat=" + s.latitude + " lng=" + s.longitude
                    + " rebuildCells=" + s.hasCellReconstructionDecision());
            return acceptLoadedSnapshot(s, refreshIntervalSec);
        } catch (Throwable t) {
            // Read/parse failure: keep last-known-good (do NOT revert to real device data mid-test).
            debug("loadSnapshot prefs error: " + t);
            return CURRENT.get();
        }
    }

    /** Commit Snapshot and delay as one accepted last-known-good transport decision. */
    private Snapshot acceptLoadedSnapshot(Snapshot snapshot, Integer refreshIntervalSec) {
        REFRESH_SCHEDULER.acceptPayloadInterval(refreshIntervalSec, true);
        return snapshot;
    }

    /**
     * Replace the app-classloader sentinel only when this target actually contains it.
     *
     * A module-classloader static cannot prove injection because the app and Xposed may load two
     * copies of the same class. Hooking the Method object from the target classloader makes the
     * probe's own call return true only when LSPosed really installed this module in that process.
     */
    private void installProbeSentinelIfPresent(ClassLoader targetClassLoader) {
        Class<?> sentinel = XposedHelpers.findClassIfExists(
                RuntimeHookSentinel.class.getName(),
                targetClassLoader);
        if (sentinel == null) return;
        XposedHelpers.findAndHookMethod(
                sentinel,
                "isHookActive",
                XC_MethodReplacement.returnConstant(true));
    }

}
