package name.caiyao.fakegps.hook;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
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

    // Hour-to-location-index mapping:
    // 7-8→0, 9→1, 10→2, 11→3, 12-13→4, 14→5, 15→6, 16+→7
    private static final int[] HOUR_TO_INDEX = {
            -1, -1, -1, -1, -1, -1, -1,  // 0-6
            0, 0, 1, 2, 3, 4, 4, 5, 6, 7, 7, 7, 7, 7, 7,  // 7-22
            -1  // 23
    };

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
     * Reads ALL columns — new fields automatically picked up from DB.
     * Hour-based location selection + micro-offset applied.
     */
    private Snapshot loadSnapshot() {
        ArrayList<Snapshot> rows = new ArrayList<>();

        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            Context ctx = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");

            Uri uri = Uri.parse("content://name.caiyao.fakegps.data.AppInfoProvider/app");
            // null projection = all columns; ORDER BY id ASC ensures stable index mapping
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
            XposedBridge.log(TAG + ": Error loading config: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            return Snapshot.PASSTHROUGH;
        }

        // Resolve which row to use based on current hour
        int hour = readHour();
        int targetIndex = (hour >= 0 && hour < HOUR_TO_INDEX.length) ? HOUR_TO_INDEX[hour] : -1;

        if (targetIndex < 0) {
            // Outside active hours — passthrough everything
            return Snapshot.PASSTHROUGH;
        }

        int index = Math.min(targetIndex, rows.size() - 1);
        Snapshot result = rows.get(index);

        // Apply micro-offset to location for realism
        if (result.hasLocation()) {
            Random rnd = new Random();
            double latOffset = rnd.nextInt(60) / 100000000.0
                    + rnd.nextInt(99999999) / 100000000000000.0;
            double lngOffset = rnd.nextInt(60) / 100000000.0
                    + rnd.nextInt(99999999) / 100000000000000.0;
            result.latitude += latOffset;
            result.longitude += lngOffset;

            writeLog(hour, index, result);
        }

        return result;
    }

    private int readHour() {
        try {
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                return 7;
            }
            File file = new File("/mnt/sdcard/database/time.txt");
            if (!file.exists()) return 7;

            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            String line = reader.readLine();
            fis.close();

            if (line != null && !line.trim().isEmpty()) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error reading hour: " + e.getMessage());
        }
        return 7;
    }

    private void writeLog(int hour, int index, Snapshot s) {
        try {
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) return;
            File file = new File("/mnt/sdcard/database/log.txt");
            FileOutputStream out = new FileOutputStream(file);
            PrintStream ps = new PrintStream(out);
            ps.printf("Hour: %d  Index: %d%n", hour, index);
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
