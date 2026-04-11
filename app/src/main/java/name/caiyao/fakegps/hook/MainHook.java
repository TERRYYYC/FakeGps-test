package name.caiyao.fakegps.hook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed module entry point.
 *
 * Architecture:
 *   1. Register ALL hooks exactly ONCE in handleLoadPackage()
 *   2. Hooks read from a shared AtomicReference<Snapshot> at invocation time
 *   3. A background timer refreshes the Snapshot (coordinates + config)
 *      WITHOUT re-registering hooks
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeGPS";

    /** Current spoofing config. Hooks read this atomically via CURRENT.get(). */
    static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.PASSTHROUGH);

    // Spoof mode constants — must match SpoofSettings.kt
    private static final String MODE_ALWAYS_ON = "always_on";
    private static final String MODE_TIME_BASED = "time_based";
    private static final String MODE_OFF = "off";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if ("name.caiyao.fakegps".equals(lpparam.packageName)) {
            return;
        }

        // 1. Load initial config
        Snapshot initial = loadSnapshot();
        CURRENT.set(initial);
        XposedBridge.log(TAG + ": Loaded config for " + lpparam.packageName
                + " | location=" + initial.hasLocation()
                + " | cell=" + initial.hasGsmCell()
                + " | lte=" + initial.hasLteCell());

        // 2. Register hooks ONCE — they read CURRENT.get() at invocation time
        HookUtils.registerAllHooks(lpparam.classLoader);

        // 3. Background refresh: update Snapshot every 60s, do NOT re-register hooks
        final Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    Snapshot refreshed = loadSnapshot();
                    CURRENT.set(refreshed);
                }
                sendEmptyMessageDelayed(1, 60 * 1000);
            }
        };
        handler.sendEmptyMessageDelayed(1, 60 * 1000);
    }

    /**
     * Load all config from ContentProvider into a Snapshot.
     * Reads spoof mode settings first, then profile data.
     * Hour-based location selection + micro-offset applied.
     */
    private Snapshot loadSnapshot() {
        Context ctx;
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            ctx = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error getting context: " + e.getMessage());
            return Snapshot.PASSTHROUGH;
        }

        // 1. Read spoof mode settings
        String spoofMode = MODE_ALWAYS_ON;
        int hourStart = 7;
        int hourEnd = 22;
        try {
            Uri settingsUri = Uri.parse("content://name.caiyao.fakegps.data.AppInfoProvider/settings");
            Cursor sc = ctx.getContentResolver().query(settingsUri, null, null, null, null);
            if (sc != null) {
                try {
                    if (sc.moveToFirst()) {
                        int modeIdx = sc.getColumnIndex("spoof_mode");
                        int startIdx = sc.getColumnIndex("active_hour_start");
                        int endIdx = sc.getColumnIndex("active_hour_end");
                        if (modeIdx >= 0) spoofMode = sc.getString(modeIdx);
                        if (startIdx >= 0) hourStart = sc.getInt(startIdx);
                        if (endIdx >= 0) hourEnd = sc.getInt(endIdx);
                    }
                } finally {
                    sc.close();
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error reading settings: " + e.getMessage());
        }

        // 2. Check mode
        if (MODE_OFF.equals(spoofMode)) {
            XposedBridge.log(TAG + ": Spoof mode OFF — passthrough");
            return Snapshot.PASSTHROUGH;
        }

        if (MODE_TIME_BASED.equals(spoofMode)) {
            int currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            boolean inRange;
            if (hourStart <= hourEnd) {
                inRange = currentHour >= hourStart && currentHour < hourEnd;
            } else {
                // Wraps midnight: e.g. 22:00 - 06:00
                inRange = currentHour >= hourStart || currentHour < hourEnd;
            }
            if (!inRange) {
                XposedBridge.log(TAG + ": Outside active hours (" + hourStart + "-" + hourEnd
                        + "), current=" + currentHour + " — passthrough");
                return Snapshot.PASSTHROUGH;
            }
        }

        // 3. Load profiles
        ArrayList<Snapshot> rows = new ArrayList<>();
        try {
            Uri uri = Uri.parse("content://name.caiyao.fakegps.data.AppInfoProvider/app");
            Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, "id ASC");
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        rows.add(Snapshot.fromCursor(cursor));
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error loading profiles: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            return Snapshot.PASSTHROUGH;
        }

        // 4. Select profile: always use first row
        Snapshot result = rows.get(0);

        // Apply micro-offset to location for realism
        if (result.hasLocation()) {
            Random rnd = new Random();
            double latOffset = rnd.nextInt(60) / 100000000.0
                    + rnd.nextInt(99999999) / 100000000000000.0;
            double lngOffset = rnd.nextInt(60) / 100000000.0
                    + rnd.nextInt(99999999) / 100000000000000.0;
            result.latitude += latOffset;
            result.longitude += lngOffset;

            writeLog(result);
        }

        return result;
    }

    private void writeLog(Snapshot s) {
        try {
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) return;
            File dir = new File(Environment.getExternalStorageDirectory(), "database");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "log.txt");
            FileOutputStream out = new FileOutputStream(file);
            PrintStream ps = new PrintStream(out);
            if (s.latitude != null && s.longitude != null) {
                ps.printf("Location: %.8f, %.8f%n", s.latitude, s.longitude);
            }
            if (s.lac != null && s.cid != null) {
                ps.printf("LAC: %d  CID: %d%n", s.lac, s.cid);
            }
            if (s.mcc != null) {
                ps.printf("MCC: %d  MNC: %s%n", s.mcc, s.mnc);
            }
            if (s.lteRsrp != null) {
                ps.printf("LTE RSRP: %d dBm%n", s.lteRsrp);
            }
            if (s.operatorName != null) {
                ps.printf("Operator: %s (%s)%n", s.operatorName, s.operatorNumeric);
            }
            ps.close();
        } catch (Exception ignored) {}
    }
}
