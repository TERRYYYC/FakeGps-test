package name.caiyao.fakegps.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.gsm.GsmCellLocation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook registration — called ONCE from {@link MainHook#handleLoadPackage}.
 * Every hook callback reads {@link MainHook#CURRENT}{@code .get()} at invocation time.
 * null field = passthrough (real device value).
 */
class HookUtils {

    private static final String TAG = "FakeGPS";
    private static final Random RND = new Random();
    /** Guards against re-hooking methods discovered at runtime (e.g. inside constructors). */
    private static final Set<String> HOOKED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /**
     * Neighbor cell bypass: CellIdentity* / CellSignalStrength* instances created from
     * neighbor_cells_json are added here. Global getter hooks (Section C/D) skip these
     * objects so their constructor-set values aren't overwritten by serving cell snapshot.
     */
    private static final Set<Object> NEIGHBOR_BYPASS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Single entry point: registers ALL hooks exactly once.
     * Hooks read current config from MainHook.CURRENT at invocation time.
     */
    static void registerAllHooks(ClassLoader cl) {
        safeHook("Location", () -> hookLocation(cl));
        safeHook("CellIdentity", () -> hookCellIdentity(cl));
        safeHook("CellIdentityGetters", () -> hookCellIdentityGetters(cl));
        safeHook("SignalStrength", () -> hookSignalStrengthGetters(cl));
        safeHook("Telephony", () -> hookTelephony(cl));
        safeHook("ServiceState", () -> hookServiceState(cl));
        safeHook("WiFi", () -> hookWifi(cl));
        safeHook("Network", () -> hookNetwork(cl));
        safeHook("PhoneStateListener", () -> hookPhoneStateListener(cl));
        safeHook("GpsStatus", () -> hookGpsStatus(cl));
        safeHook("LocationManagerCtor", () -> hookLocationManagerConstructor(cl));
        safeHook("TelephonyCallback", () -> hookTelephonyCallback(cl));
        safeHook("Connectivity", () -> hookConnectivity(cl));
        safeHook("PhysicalChannelConfig", () -> hookPhysicalChannelConfig(cl));
        safeHook("SubscriptionAware", () -> hookSubscriptionAware(cl));
        safeHook("FusedLocation", () -> hookFusedLocation(cl));
    }

    private static void safeHook(String group, Runnable r) {
        try {
            r.run();
            XposedBridge.log(TAG + ": " + group + " hooks registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + group + " hooks FAILED: " + t.getMessage());
        }
    }

    // ==========================================================================
    // A. LOCATION HOOKS
    // ==========================================================================

    private static void hookLocation(ClassLoader cl) {

        // LocationManager.getLastLocation()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "getLastLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        param.setResult(createFakeLocation(s));
                    }
                }));

        // LocationManager.getLastKnownLocation(String provider)
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "getLastKnownLocation", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        param.setResult(createFakeLocation(s));
                    }
                }));

        // LocationManager.getProviders(...)
        tryHook(() -> XposedBridge.hookAllMethods(
                LocationManager.class, "getProviders", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        ArrayList<String> providers = new ArrayList<>();
                        providers.add(LocationManager.GPS_PROVIDER);
                        providers.add(LocationManager.NETWORK_PROVIDER);
                        param.setResult(providers);
                    }
                }));

        // LocationManager.getBestProvider(Criteria, boolean)
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "getBestProvider",
                Criteria.class, Boolean.TYPE, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        param.setResult(LocationManager.GPS_PROVIDER);
                    }
                }));

        // LocationManager.requestLocationUpdates(...) — all public overloads
        for (Method method : LocationManager.class.getDeclaredMethods()) {
            if (!"requestLocationUpdates".equals(method.getName())) continue;
            if (Modifier.isAbstract(method.getModifiers())) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;

            tryHook(() -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Snapshot s = MainHook.CURRENT.get();
                    if (!s.hasLocation()) return;

                    LocationListener ll = findLocationListener(param.args);
                    if (ll != null) {
                        try {
                            ll.onLocationChanged(createFakeLocation(s));
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": requestLocationUpdates callback: " + e);
                        }
                    }
                }
            }));
        }

        // LocationManager.requestSingleUpdate(...) — all public overloads
        // NOTE: old code had trailing space "requestSingleUpdate " (P0 bug) — fixed here
        for (Method method : LocationManager.class.getDeclaredMethods()) {
            if (!"requestSingleUpdate".equals(method.getName())) continue;
            if (Modifier.isAbstract(method.getModifiers())) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;

            tryHook(() -> XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Snapshot s = MainHook.CURRENT.get();
                    if (!s.hasLocation()) return;

                    LocationListener ll = findLocationListener(param.args);
                    if (ll != null) {
                        try {
                            ll.onLocationChanged(createFakeLocation(s));
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": requestSingleUpdate callback: " + e);
                        }
                    }
                }
            }));
        }

        // LocationManager.addNmeaListener — block NMEA data to prevent real location leak
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "addNmeaListener",
                GpsStatus.NmeaListener.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        param.setResult(false);
                    }
                }));

        // LocationManager.getCurrentLocation (API 30+) — new one-shot API
        // Multiple overloads: (String, CancellationSignal, Executor, Consumer)
        //                     (String, LocationRequest, CancellationSignal, Executor, Consumer)
        if (Build.VERSION.SDK_INT >= 30) {
            tryHook(() -> XposedBridge.hookAllMethods(
                    LocationManager.class, "getCurrentLocation", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasLocation()) return;

                            // Find the Consumer<Location> arg by interface type
                            // (lambda/anonymous classes don't have "Consumer" in their class name)
                            for (int i = param.args.length - 1; i >= 0; i--) {
                                if (param.args[i] instanceof java.util.function.Consumer) {
                                    try {
                                        @SuppressWarnings("unchecked")
                                        java.util.function.Consumer<Location> consumer =
                                                (java.util.function.Consumer<Location>) param.args[i];
                                        consumer.accept(createFakeLocation(s));
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + ": getCurrentLocation consumer: " + t);
                                    }
                                    param.setResult(null); // prevent real location request
                                    return;
                                }
                            }
                        }
                    }));
        }
    }

    // ==========================================================================
    // B. CELL IDENTITY — getCellLocation / getAllCellInfo
    // ==========================================================================

    private static void hookCellIdentity(ClassLoader cl) {

        // TelephonyManager.getCellLocation()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "getCellLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasGsmCell()) return;
                        GsmCellLocation loc = new GsmCellLocation();
                        loc.setLacAndCid(s.lac, s.cid);
                        param.setResult(loc);
                    }
                }));

        // TelephonyManager.getPhoneCount() — only override when spoofing cells, otherwise passthrough
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager", cl,
                    "getPhoneCount", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (s.hasGsmCell() || s.hasLteCell() || s.hasNrCell()) {
                                param.setResult(1);
                            }
                            // else: passthrough real phone count (preserves dual-SIM)
                        }
                    }));
        }

        // TelephonyManager.getNeighboringCellInfo() — deprecated but still queried
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "getNeighboringCellInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasGsmCell()) return;
                        param.setResult(new ArrayList<>());
                    }
                }));

        // TelephonyManager.getAllCellInfo()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "getAllCellInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                        param.setResult(buildCellInfoList(s));
                    }
                }));

        // TelephonyManager.requestCellInfoUpdate() — intercept the call, hook concrete callback class
        if (Build.VERSION.SDK_INT >= 29) {
            tryHook(() -> XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.telephony.TelephonyManager", cl),
                    "requestCellInfoUpdate", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Class<?> cbBase = XposedHelpers.findClass(
                                    "android.telephony.TelephonyManager$CellInfoCallback", cl);
                            for (Object arg : param.args) {
                                if (arg != null && cbBase.isInstance(arg)) {
                                    hookCellInfoCallbackInstance(arg, cl);
                                    break;
                                }
                            }
                        }
                    }));
        }

        // CellInfo.isRegistered() — always true for our fake cells
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.CellInfo", cl,
                "isRegistered", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.hasGsmCell() || s.hasLteCell() || s.hasNrCell()) {
                            param.setResult(true);
                        }
                    }
                }));
    }

    // ==========================================================================
    // C. CELL IDENTITY GETTER HOOKS (per-subclass field overrides)
    // ==========================================================================

    private static void hookCellIdentityGetters(ClassLoader cl) {
        // GSM
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getMcc", s -> s.mcc);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getMnc", s -> s.mnc);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getLac", s -> s.lac);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getCid", s -> s.cid);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getArfcn", s -> s.arfcn);
        hookGetter(cl, "android.telephony.CellIdentityGsm", "getBsic", s -> s.bsic);

        // WCDMA
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getMcc", s -> s.mcc);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getMnc", s -> s.mnc);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getLac", s -> s.lac);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getCid", s -> s.cid);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getPsc", s -> s.psc);
        hookGetter(cl, "android.telephony.CellIdentityWcdma", "getUarfcn", s -> s.uarfcn);

        // LTE
        hookGetter(cl, "android.telephony.CellIdentityLte", "getMcc", s -> s.mcc);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getMnc", s -> s.mnc);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getTac", s -> s.tac);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getCi", s -> s.ci);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getPci", s -> s.pci);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getEarfcn", s -> s.earfcn);
        hookGetter(cl, "android.telephony.CellIdentityLte", "getBandwidth", s -> s.lteBandwidth);

        // CDMA — map lac→networkId, cid→baseStationId
        hookGetter(cl, "android.telephony.CellIdentityCdma", "getNetworkId", s -> s.lac);
        hookGetter(cl, "android.telephony.CellIdentityCdma", "getBaseStationId", s -> s.cid);

        // NR/5G (API 29+)
        if (Build.VERSION.SDK_INT >= 29) {
            hookGetter(cl, "android.telephony.CellIdentityNr", "getMccString",
                    s -> s.mcc != null ? String.valueOf(s.mcc) : null);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getMncString",
                    s -> s.mnc != null ? String.valueOf(s.mnc) : null);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getNci", s -> s.nci);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getNrarfcn", s -> s.nrarfcn);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getPci", s -> s.nrPci);
            hookGetter(cl, "android.telephony.CellIdentityNr", "getTac", s -> s.nrTac);
        }

        // Common: CellIdentity.getMccString / getMncString (API 28+)
        if (Build.VERSION.SDK_INT >= 28) {
            for (String cls : new String[]{
                    "android.telephony.CellIdentityGsm",
                    "android.telephony.CellIdentityWcdma",
                    "android.telephony.CellIdentityLte"}) {
                hookGetter(cl, cls, "getMccString",
                        s -> s.mcc != null ? String.valueOf(s.mcc) : null);
                hookGetter(cl, cls, "getMncString",
                        s -> s.mnc != null ? String.valueOf(s.mnc) : null);
            }
        }
    }

    // ==========================================================================
    // D. SIGNAL STRENGTH GETTER HOOKS
    // ==========================================================================

    private static void hookSignalStrengthGetters(ClassLoader cl) {
        // GSM signal
        hookSignal(cl, "android.telephony.CellSignalStrengthGsm", "getDbm", s -> s.gsmRssi);
        hookSignal(cl, "android.telephony.CellSignalStrengthGsm", "getBitErrorRate", s -> s.gsmBer);
        if (Build.VERSION.SDK_INT >= 26) {
            hookSignal(cl, "android.telephony.CellSignalStrengthGsm", "getTimingAdvance", s -> s.gsmTa);
        }

        // WCDMA signal
        hookSignal(cl, "android.telephony.CellSignalStrengthWcdma", "getDbm", s -> s.wcdmaRssi);
        if (Build.VERSION.SDK_INT >= 28) {
            hookSignal(cl, "android.telephony.CellSignalStrengthWcdma", "getRscp", s -> s.wcdmaRscp);
            hookSignal(cl, "android.telephony.CellSignalStrengthWcdma", "getEcNo", s -> s.wcdmaEcno);
        }

        // LTE signal
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getDbm", s -> s.lteRsrp);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRsrp", s -> s.lteRsrp);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRsrq", s -> s.lteRsrq);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRssnr", s -> s.lteSinr);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getCqi", s -> s.lteCqi);
        hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getTimingAdvance", s -> s.lteTa);
        if (Build.VERSION.SDK_INT >= 29) {
            hookSignal(cl, "android.telephony.CellSignalStrengthLte", "getRssi", s -> s.lteRssi);
        }

        // NR/5G signal (API 29+)
        if (Build.VERSION.SDK_INT >= 29) {
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getSsRsrp", s -> s.nrSsRsrp);
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getSsRsrq", s -> s.nrSsRsrq);
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getSsSinr", s -> s.nrSsSinr);
            hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getDbm", s -> s.nrSsRsrp);

            if (Build.VERSION.SDK_INT >= 31) {
                hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getCsiRsrp", s -> s.nrCsiRsrp);
                hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getCsiRsrq", s -> s.nrCsiRsrq);
                hookSignal(cl, "android.telephony.CellSignalStrengthNr", "getCsiSinr", s -> s.nrCsiSinr);
            }
        }

        // CDMA signal — map to GSM values as fallback
        hookSignal(cl, "android.telephony.CellSignalStrengthCdma", "getDbm", s -> s.gsmRssi);
    }

    // ==========================================================================
    // E. TELEPHONY MANAGER — operator, network type
    // ==========================================================================

    private static void hookTelephony(ClassLoader cl) {
        // Operator info
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkOperatorName",
                s -> s.operatorName);
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkOperator",
                s -> s.operatorNumeric);
        hookGetter(cl, "android.telephony.TelephonyManager", "getSimOperator",
                s -> s.simOperator);
        hookGetter(cl, "android.telephony.TelephonyManager", "getSimOperatorName",
                s -> s.simOperatorName);
        hookGetter(cl, "android.telephony.TelephonyManager", "getSimCountryIso",
                s -> s.simCountryIso);
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkCountryIso",
                s -> s.networkCountryIso);
        hookGetter(cl, "android.telephony.TelephonyManager", "isNetworkRoaming",
                s -> s.isRoaming);
        hookGetter(cl, "android.telephony.TelephonyManager", "getPhoneType",
                s -> s.phoneType);

        // Network type
        hookGetter(cl, "android.telephony.TelephonyManager", "getNetworkType",
                s -> s.networkType);

        // API 24+: getDataNetworkType, getVoiceNetworkType
        if (Build.VERSION.SDK_INT >= 24) {
            hookGetter(cl, "android.telephony.TelephonyManager", "getDataNetworkType",
                    s -> s.dataNetworkType != null ? s.dataNetworkType : s.networkType);
            hookGetter(cl, "android.telephony.TelephonyManager", "getVoiceNetworkType",
                    s -> s.voiceNetworkType);
        }
    }

    // ==========================================================================
    // F. SERVICE STATE
    // ==========================================================================

    private static void hookServiceState(ClassLoader cl) {
        hookGetter(cl, "android.telephony.TelephonyManager", "getDataState",
                s -> s.dataState);
        hookGetter(cl, "android.telephony.TelephonyManager", "getDataActivity",
                s -> s.dataActivity);

        // ServiceState.getState()
        hookGetter(cl, "android.telephony.ServiceState", "getState",
                s -> s.serviceState);

        // TelephonyDisplayInfo.getOverrideNetworkType() (API 30+)
        if (Build.VERSION.SDK_INT >= 30) {
            hookGetter(cl, "android.telephony.TelephonyDisplayInfo", "getOverrideNetworkType",
                    s -> s.overrideNetworkType);
        }

        // PhysicalChannelConfig: Section N. TelephonyCallback: Section L.
    }

    // ==========================================================================
    // G. WIFI HOOKS
    // ==========================================================================

    private static void hookWifi(ClassLoader cl) {

        // WifiManager.getScanResults() — return empty to hide real APs
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "getScanResults", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.wifiHidden != null && s.wifiHidden) {
                            param.setResult(new ArrayList<>());
                        }
                    }
                }));

        // WifiManager.getWifiState() — must be consistent with isWifiEnabled
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "getWifiState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.wifiEnabled != null) {
                            // WIFI_STATE_ENABLED=3, WIFI_STATE_DISABLED=1
                            param.setResult(s.wifiEnabled ? 3 : 1);
                        }
                    }
                }));

        // WifiManager.isWifiEnabled()
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "isWifiEnabled", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.wifiEnabled != null) {
                            param.setResult(s.wifiEnabled);
                        }
                    }
                }));

        // WifiInfo getters
        hookGetter(cl, "android.net.wifi.WifiInfo", "getMacAddress", s -> s.wifiMac);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getSSID",
                s -> s.wifiSsid != null ? "\"" + s.wifiSsid + "\"" : null);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getBSSID", s -> s.wifiBssid);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getRssi", s -> s.wifiRssi);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getFrequency", s -> s.wifiFrequency);
        hookGetter(cl, "android.net.wifi.WifiInfo", "getLinkSpeed", s -> s.wifiLinkSpeed);

        // API 29+
        if (Build.VERSION.SDK_INT >= 29) {
            hookGetter(cl, "android.net.wifi.WifiInfo", "getTxLinkSpeedMbps", s -> s.wifiTxLinkSpeed);
            hookGetter(cl, "android.net.wifi.WifiInfo", "getRxLinkSpeedMbps", s -> s.wifiRxLinkSpeed);
        }
        // API 30+
        if (Build.VERSION.SDK_INT >= 30) {
            hookGetter(cl, "android.net.wifi.WifiInfo", "getWifiStandard", s -> s.wifiStandard);
        }
        // API 31+
        if (Build.VERSION.SDK_INT >= 31) {
            hookGetter(cl, "android.net.wifi.WifiInfo", "getCurrentSecurityType", s -> s.wifiSecurityType);
        }

        // WifiInfo.getIpAddress() — convert dotted string to int if configured
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo", cl,
                "getIpAddress", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.wifiIp != null) {
                            param.setResult(ipToInt(s.wifiIp));
                        }
                    }
                }));
    }

    // ==========================================================================
    // H. NETWORK CONNECTIVITY HOOKS
    // ==========================================================================

    private static void hookNetwork(ClassLoader cl) {
        // NetworkInfo.getTypeName()
        hookGetter(cl, "android.net.NetworkInfo", "getTypeName", s -> s.connectionType);

        // NetworkInfo connectivity — always connected if we have config
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", cl,
                "isConnected", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.connectionType != null) {
                            param.setResult(true);
                        }
                    }
                }));

        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", cl,
                "isConnectedOrConnecting", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.connectionType != null) {
                            param.setResult(true);
                        }
                    }
                }));

        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.NetworkInfo", cl,
                "isAvailable", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.connectionType != null) {
                            param.setResult(true);
                        }
                    }
                }));

        // LinkProperties hooks (modern path)
        hookGetter(cl, "android.net.LinkProperties", "getInterfaceName", s -> s.interfaceName);

        // ConnectivityManager.getActiveNetworkInfo() — ensure type matches connectionType
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.ConnectivityManager", cl,
                "getActiveNetworkInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.connectionType == null) return;
                        // Let existing NetworkInfo hooks handle field overrides on the result
                    }
                }));

        // LinkProperties.getDnsServers() — replace DNS list if configured
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties", cl,
                "getDnsServers", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.dnsPrimary == null) return;
                        try {
                            List<java.net.InetAddress> dns = new ArrayList<>();
                            dns.add(java.net.InetAddress.getByName(s.dnsPrimary));
                            if (s.dnsSecondary != null) {
                                dns.add(java.net.InetAddress.getByName(s.dnsSecondary));
                            }
                            param.setResult(dns);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": DNS override failed: " + t);
                        }
                    }
                }));

        // NetworkInterface.getName() — override interface name
        hookGetter(cl, "java.net.NetworkInterface", "getName", s -> s.interfaceName);
    }

    // ==========================================================================
    // I. PHONE STATE LISTENER — intercept TelephonyManager.listen(),
    //    hook concrete listener class methods (NOT base class)
    // ==========================================================================

    private static void hookPhoneStateListener(ClassLoader cl) {
        // Intercept TelephonyManager.listen(PhoneStateListener, int) to discover concrete listeners
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "listen",
                XposedHelpers.findClass("android.telephony.PhoneStateListener", cl),
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object listener = param.args[0];
                        if (listener == null) return;
                        hookPhoneStateListenerInstance(listener, cl);
                    }
                }));
    }

    /** Hook methods on a concrete PhoneStateListener subclass. Dedup via HOOKED set. */
    private static void hookPhoneStateListenerInstance(Object listener, ClassLoader cl) {
        Class<?> listenerClass = listener.getClass();
        String className = listenerClass.getName();

        // onCellLocationChanged(CellLocation) — VOID: modify args[0]
        String key1 = "psl#" + className + "#onCellLocationChanged";
        if (HOOKED.add(key1)) {
            tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                    "onCellLocationChanged", CellLocation.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell()) return;
                            GsmCellLocation loc = new GsmCellLocation();
                            loc.setLacAndCid(s.lac, s.cid);
                            param.args[0] = loc;
                        }
                    }));
        }

        // onCellInfoChanged(List<CellInfo>) — VOID: modify args[0]
        String key2 = "psl#" + className + "#onCellInfoChanged";
        if (HOOKED.add(key2)) {
            tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                    "onCellInfoChanged", List.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                            param.args[0] = buildCellInfoList(s);
                        }
                    }));
        }

        // onSignalStrengthsChanged(SignalStrength) — VOID: getter hooks handle fields
        String key3 = "psl#" + className + "#onSignalStrengthsChanged";
        if (HOOKED.add(key3)) {
            tryHook(() -> {
                Class<?> ssClass = XposedHelpers.findClass("android.telephony.SignalStrength", cl);
                XposedHelpers.findAndHookMethod(listenerClass,
                        "onSignalStrengthsChanged", ssClass, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                // Individual field overrides handled by CellSignalStrength getter hooks
                            }
                        });
            });
        }

        // onServiceStateChanged(ServiceState) — VOID: modify internal fields
        String key4 = "psl#" + className + "#onServiceStateChanged";
        if (HOOKED.add(key4)) {
            tryHook(() -> {
                Class<?> stateClass = XposedHelpers.findClass("android.telephony.ServiceState", cl);
                XposedHelpers.findAndHookMethod(listenerClass,
                        "onServiceStateChanged", stateClass, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Snapshot s = MainHook.CURRENT.get();
                                if (s.serviceState != null && param.args[0] != null) {
                                    try {
                                        XposedHelpers.setIntField(param.args[0], "mVoiceRegState", s.serviceState);
                                        if (s.dataState != null) {
                                            XposedHelpers.setIntField(param.args[0], "mDataRegState", s.dataState);
                                        }
                                    } catch (Throwable ignored) {
                                        // Field names may vary across Android versions
                                    }
                                }
                            }
                        });
            });
        }
    }

    // ==========================================================================
    // J. GPS STATUS HOOKS
    // ==========================================================================

    private static void hookGpsStatus(ClassLoader cl) {

        // addGpsStatusListener — simulate GPS fix events
        tryHook(() -> XposedHelpers.findAndHookMethod(
                LocationManager.class, "addGpsStatusListener",
                GpsStatus.Listener.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        if (param.args[0] != null) {
                            GpsStatus.Listener listener = (GpsStatus.Listener) param.args[0];
                            listener.onGpsStatusChanged(1); // GPS_EVENT_STARTED
                            listener.onGpsStatusChanged(3); // GPS_EVENT_FIRST_FIX
                        }
                    }
                }));

        // getGpsStatus — fake satellite constellation
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.location.LocationManager", cl,
                "getGpsStatus", GpsStatus.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;

                        GpsStatus gss = (GpsStatus) param.getResult();
                        if (gss == null) return;

                        try {
                            int svCount = 5;
                            int[] prns = {1, 2, 3, 4, 5};
                            float[] snrs = {30f, 28f, 25f, 22f, 20f};
                            float[] elevations = {60f, 45f, 30f, 15f, 10f};
                            float[] azimuths = {0f, 72f, 144f, 216f, 288f};
                            int ephemerisMask = 0x1f;
                            int almanacMask = 0x1f;
                            int usedInFixMask = 0x1f;

                            XposedHelpers.callMethod(gss, "setStatus",
                                    svCount, prns, snrs, elevations, azimuths,
                                    ephemerisMask, almanacMask, usedInFixMask);
                            param.setResult(gss);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": GpsStatus.setStatus failed: " + t);
                        }
                    }
                }));
    }

    // ==========================================================================
    // K. LOCATION MANAGER CONSTRUCTOR — hook internal reportLocation
    // ==========================================================================

    private static void hookLocationManagerConstructor(ClassLoader cl) {
        tryHook(() -> XposedBridge.hookAllConstructors(LocationManager.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length < 2) return;

                Context context = (Context) param.args[0];
                String ctxClass = context.getClass().getName();

                // Dedup: only hook each context class once
                if (HOOKED.add("ctx#" + ctxClass + "#checkCallingOrSelfPermission")) {
                    tryHook(() -> XposedHelpers.findAndHookMethod(
                            context.getClass(), "checkCallingOrSelfPermission",
                            String.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) {
                                    if (String.valueOf(p.args[0]).contains("INSTALL_LOCATION_PROVIDER")) {
                                        p.setResult(PackageManager.PERMISSION_GRANTED);
                                    }
                                }
                            }));
                }

                // Hook internal service methods — dedup by Method identity
                Object service = param.args[1];
                String svcClass = service.getClass().getName();
                for (Method m : service.getClass().getMethods()) {
                    String key = "svc#" + svcClass + "#" + m.getName();
                    if ("reportLocation".equals(m.getName()) && HOOKED.add(key)) {
                        m.setAccessible(true);
                        tryHook(() -> XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                Snapshot s = MainHook.CURRENT.get();
                                if (!s.hasLocation()) return;
                                if (p.args[0] instanceof Location) {
                                    Location loc = (Location) p.args[0];
                                    loc.setLatitude(s.latitude);
                                    loc.setLongitude(s.longitude);
                                    if (s.altitude != null) loc.setAltitude(s.altitude);
                                    if (s.accuracy != null) loc.setAccuracy(s.accuracy);
                                }
                            }
                        }));
                    } else if (("getLastLocation".equals(m.getName())
                            || "getLastKnownLocation".equals(m.getName())) && HOOKED.add(key)) {
                        tryHook(() -> XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Snapshot s = MainHook.CURRENT.get();
                                if (!s.hasLocation()) return;
                                if (p.getResult() instanceof Location) {
                                    Location loc = (Location) p.getResult();
                                    loc.setLatitude(s.latitude);
                                    loc.setLongitude(s.longitude);
                                    if (s.altitude != null) loc.setAltitude(s.altitude);
                                    if (s.accuracy != null) loc.setAccuracy(s.accuracy);
                                }
                            }
                        }));
                    }
                }
            }
        }));
    }

    // ==========================================================================
    // L. TELEPHONY CALLBACK (API 31+) — dual-path with PhoneStateListener
    // ==========================================================================

    private static void hookTelephonyCallback(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 31) return;

        try {
            Class<?> tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", cl);
            Class<?> callbackClass = XposedHelpers.findClass("android.telephony.TelephonyCallback", cl);

            // hookAllMethods covers both 2-param (Executor, TelephonyCallback)
            // and 3-param API 33+ (Executor, int includeLocationData, TelephonyCallback)
            XposedBridge.hookAllMethods(tmClass, "registerTelephonyCallback",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Find the TelephonyCallback arg among all params
                            for (Object arg : param.args) {
                                if (arg != null && callbackClass.isInstance(arg)) {
                                    hookTelephonyCallbackInstance(arg, cl);
                                    break;
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TelephonyCallback registration hook failed: " + t);
        }
    }

    /** Hook methods on a concrete TelephonyCallback instance based on which interfaces it implements. */
    private static void hookTelephonyCallbackInstance(Object callback, ClassLoader cl) {
        Class<?> cbClass = callback.getClass();
        String cbName = cbClass.getName();

        // CellInfoListener.onCellInfoChanged(List<CellInfo>)
        hookCallbackMethod(cl, cbClass, cbName,
                "android.telephony.TelephonyCallback$CellInfoListener",
                "onCellInfoChanged", List.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                        param.args[0] = buildCellInfoList(s);
                    }
                });

        // CellLocationListener.onCellLocationChanged(CellLocation)
        hookCallbackMethod(cl, cbClass, cbName,
                "android.telephony.TelephonyCallback$CellLocationListener",
                "onCellLocationChanged", CellLocation.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasGsmCell()) return;
                        GsmCellLocation loc = new GsmCellLocation();
                        loc.setLacAndCid(s.lac, s.cid);
                        param.args[0] = loc;
                    }
                });

        // SignalStrengthsListener.onSignalStrengthsChanged(SignalStrength)
        try {
            Class<?> ssClass = XposedHelpers.findClass("android.telephony.SignalStrength", cl);
            hookCallbackMethod(cl, cbClass, cbName,
                    "android.telephony.TelephonyCallback$SignalStrengthsListener",
                    "onSignalStrengthsChanged", ssClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Individual field overrides handled by CellSignalStrength getter hooks
                        }
                    });
        } catch (Throwable ignored) {}

        // ServiceStateListener.onServiceStateChanged(ServiceState)
        try {
            Class<?> stateClass = XposedHelpers.findClass("android.telephony.ServiceState", cl);
            hookCallbackMethod(cl, cbClass, cbName,
                    "android.telephony.TelephonyCallback$ServiceStateListener",
                    "onServiceStateChanged", stateClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (s.serviceState != null && param.args[0] != null) {
                                try {
                                    XposedHelpers.setIntField(param.args[0], "mVoiceRegState", s.serviceState);
                                    if (s.dataState != null) {
                                        XposedHelpers.setIntField(param.args[0], "mDataRegState", s.dataState);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // DisplayInfoListener.onDisplayInfoChanged(TelephonyDisplayInfo)
        try {
            Class<?> dispClass = XposedHelpers.findClass("android.telephony.TelephonyDisplayInfo", cl);
            hookCallbackMethod(cl, cbClass, cbName,
                    "android.telephony.TelephonyCallback$DisplayInfoListener",
                    "onDisplayInfoChanged", dispClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Override handled by TelephonyDisplayInfo getter hooks
                        }
                    });
        } catch (Throwable ignored) {}

        // PhysicalChannelConfigListener.onPhysicalChannelConfigChanged(List<PhysicalChannelConfig>)
        hookCallbackMethod(cl, cbClass, cbName,
                "android.telephony.TelephonyCallback$PhysicalChannelConfigListener",
                "onPhysicalChannelConfigChanged", List.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasPhysicalChannelConfig()) return;
                        // Replace list with single fake PCC — getter hooks override fields
                        try {
                            Class<?> pccClass = XposedHelpers.findClass(
                                    "android.telephony.PhysicalChannelConfig", cl);
                            Object fakePcc = XposedHelpers.newInstance(pccClass);
                            ArrayList<Object> pccList = new ArrayList<>();
                            pccList.add(fakePcc);
                            param.args[0] = pccList;
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": PCC callback override failed: " + t);
                        }
                    }
                });
    }

    /** Hook a callback method if the concrete class implements the given interface. Dedup via HOOKED set. */
    private static void hookCallbackMethod(ClassLoader cl, Class<?> cbClass, String cbName,
                                           String interfaceName, String methodName,
                                           Class<?> paramType, XC_MethodHook hook) {
        try {
            Class<?> iface = XposedHelpers.findClass(interfaceName, cl);
            if (!iface.isAssignableFrom(cbClass)) return;
            String key = "tcb#" + cbName + "#" + methodName;
            if (!HOOKED.add(key)) return;
            XposedHelpers.findAndHookMethod(cbClass, methodName, paramType, hook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TelephonyCallback." + methodName + " hook skipped: " + t.getMessage());
        }
    }

    /** Hook concrete CellInfoCallback subclass's onCellInfo() method. Dedup via HOOKED set. */
    private static void hookCellInfoCallbackInstance(Object callback, ClassLoader cl) {
        Class<?> cbClass = callback.getClass();
        String key = "cic#" + cbClass.getName() + "#onCellInfo";
        if (!HOOKED.add(key)) return;
        tryHook(() -> XposedHelpers.findAndHookMethod(cbClass, "onCellInfo", List.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                        param.args[0] = buildCellInfoList(s);
                    }
                }));
    }

    // ==========================================================================
    // M. CONNECTIVITY — LinkProperties, NetworkInterface, DNS
    // ==========================================================================

    private static void hookConnectivity(ClassLoader cl) {
        // LinkProperties.getLinkAddresses() — for local IP discovery
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties", cl,
                "getLinkAddresses", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.localIpv4 == null && s.localIpv6 == null) return;
                        // Replace addresses with fake ones
                        try {
                            List<?> original = (List<?>) param.getResult();
                            if (original == null || original.isEmpty()) return;
                            ArrayList<Object> fakeAddrs = new ArrayList<>();
                            for (Object linkAddr : original) {
                                java.net.InetAddress addr = (java.net.InetAddress)
                                        XposedHelpers.callMethod(linkAddr, "getAddress");
                                String addrStr = addr.getHostAddress();
                                if (addrStr != null && addrStr.contains(".") && s.localIpv4 != null) {
                                    // Replace IPv4
                                    Object fake = XposedHelpers.newInstance(
                                            linkAddr.getClass(),
                                            java.net.InetAddress.getByName(s.localIpv4),
                                            XposedHelpers.callMethod(linkAddr, "getPrefixLength"));
                                    fakeAddrs.add(fake);
                                } else if (addrStr != null && addrStr.contains(":") && s.localIpv6 != null) {
                                    // Replace IPv6
                                    Object fake = XposedHelpers.newInstance(
                                            linkAddr.getClass(),
                                            java.net.InetAddress.getByName(s.localIpv6),
                                            XposedHelpers.callMethod(linkAddr, "getPrefixLength"));
                                    fakeAddrs.add(fake);
                                } else {
                                    fakeAddrs.add(linkAddr);
                                }
                            }
                            param.setResult(fakeAddrs);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": LinkAddress override failed: " + t);
                        }
                    }
                }));

        // NetworkInterface.getInetAddresses() — for apps enumerating interfaces
        tryHook(() -> XposedBridge.hookAllMethods(
                java.net.NetworkInterface.class, "getInetAddresses", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.localIpv4 == null && s.localIpv6 == null) return;
                        @SuppressWarnings("unchecked")
                        java.util.Enumeration<java.net.InetAddress> original =
                                (java.util.Enumeration<java.net.InetAddress>) param.getResult();
                        if (original == null) return;

                        ArrayList<java.net.InetAddress> modified = new ArrayList<>();
                        while (original.hasMoreElements()) {
                            java.net.InetAddress addr = original.nextElement();
                            String addrStr = addr.getHostAddress();
                            try {
                                if (addrStr != null && addrStr.contains(".") && s.localIpv4 != null
                                        && !addr.isLoopbackAddress()) {
                                    modified.add(java.net.InetAddress.getByName(s.localIpv4));
                                } else if (addrStr != null && addrStr.contains(":") && s.localIpv6 != null
                                        && !addr.isLoopbackAddress()) {
                                    modified.add(java.net.InetAddress.getByName(s.localIpv6));
                                } else {
                                    modified.add(addr);
                                }
                            } catch (Throwable t) {
                                modified.add(addr);
                            }
                        }
                        param.setResult(Collections.enumeration(modified));
                    }
                }));

        // LinkProperties.getRoutes() — replace gateway address
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.LinkProperties", cl,
                "getRoutes", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.gateway == null) return;
                        try {
                            @SuppressWarnings("unchecked")
                            List<Object> original = (List<Object>) param.getResult();
                            if (original == null || original.isEmpty()) return;
                            ArrayList<Object> fakeRoutes = new ArrayList<>();
                            java.net.InetAddress fakeGw = java.net.InetAddress.getByName(s.gateway);
                            Class<?> routeInfoClass = XposedHelpers.findClass(
                                    "android.net.RouteInfo", cl);
                            for (Object route : original) {
                                java.net.InetAddress gw = (java.net.InetAddress)
                                        XposedHelpers.callMethod(route, "getGateway");
                                if (gw != null && !gw.isAnyLocalAddress()) {
                                    // Replace route with same destination but fake gateway
                                    Object dest = XposedHelpers.callMethod(route, "getDestination");
                                    String iface = (String) XposedHelpers.callMethod(route, "getInterface");
                                    try {
                                        Object fake = XposedHelpers.newInstance(routeInfoClass,
                                                dest, fakeGw, iface);
                                        fakeRoutes.add(fake);
                                        continue;
                                    } catch (Throwable ignored) {}
                                }
                                fakeRoutes.add(route);
                            }
                            param.setResult(fakeRoutes);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": Route/gateway override failed: " + t);
                        }
                    }
                }));

        // WifiManager.getDhcpInfo() — override gateway + subnet mask
        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", cl,
                "getDhcpInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.gateway == null && s.subnetMask == null
                                && s.localIpv4 == null && s.dnsPrimary == null
                                && s.dnsSecondary == null) return;
                        Object dhcp = param.getResult();
                        if (dhcp == null) return;
                        try {
                            if (s.gateway != null) {
                                XposedHelpers.setIntField(dhcp, "gateway",
                                        ipToInt(s.gateway));
                            }
                            if (s.subnetMask != null) {
                                XposedHelpers.setIntField(dhcp, "netmask",
                                        ipToInt(s.subnetMask));
                            }
                            if (s.localIpv4 != null) {
                                XposedHelpers.setIntField(dhcp, "ipAddress",
                                        ipToInt(s.localIpv4));
                            }
                            if (s.dnsPrimary != null) {
                                XposedHelpers.setIntField(dhcp, "dns1",
                                        ipToInt(s.dnsPrimary));
                            }
                            if (s.dnsSecondary != null) {
                                XposedHelpers.setIntField(dhcp, "dns2",
                                        ipToInt(s.dnsSecondary));
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": DhcpInfo override failed: " + t);
                        }
                    }
                }));
    }


    // ==========================================================================
    // N. PHYSICAL CHANNEL CONFIG (API 29+) — getter hooks + callback interception
    // ==========================================================================

    private static void hookPhysicalChannelConfig(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 29) return;

        try {
            Class<?> pccClass = XposedHelpers.findClass("android.telephony.PhysicalChannelConfig", cl);

            // PhysicalChannelConfig.getCellBandwidthDownlinkKhz() (API 29+)
            hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                    "getCellBandwidthDownlinkKhz", s -> s.cellBandwidthDownlink);

            // PhysicalChannelConfig.getPhysicalCellId() (API 29+)
            hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                    "getPhysicalCellId", s -> s.physicalCellId);

            // PhysicalChannelConfig.getConnectionStatus() (API 29+) — PRIMARY_SERVING=1
            hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                    "getConnectionStatus", s -> s.hasPhysicalChannelConfig() ? 1 : null);

            // API 30+: getNetworkType()
            if (Build.VERSION.SDK_INT >= 30) {
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getNetworkType", s -> s.networkType);
            }

            // API 31+: getBand(), getDownlinkChannelNumber(), getUplinkChannelNumber()
            if (Build.VERSION.SDK_INT >= 31) {
                // getBand() returns band number (e.g. 1,3,7 for LTE; 41,77,78 for NR)
                // NOT bandwidth — these are distinct concepts
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getBand", s -> s.band);
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getDownlinkChannelNumber", s -> {
                            // Map to appropriate ARFCN based on available cell config
                            if (s.nrarfcn != null) return s.nrarfcn;
                            if (s.earfcn != null) return s.earfcn;
                            if (s.uarfcn != null) return s.uarfcn;
                            return s.arfcn;
                        });
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getUplinkChannelNumber", s -> {
                            // Symmetric with downlink — same ARFCN cascade
                            if (s.nrarfcn != null) return s.nrarfcn;
                            if (s.earfcn != null) return s.earfcn;
                            if (s.uarfcn != null) return s.uarfcn;
                            return s.arfcn;
                        });
            }

            // API 33+: getCellBandwidthUplinkKhz()
            if (Build.VERSION.SDK_INT >= 33) {
                hookGetter(cl, "android.telephony.PhysicalChannelConfig",
                        "getCellBandwidthUplinkKhz", s -> s.cellBandwidthDownlink);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PhysicalChannelConfig class not found, skipping: " + t.getMessage());
        }
    }

    // ==========================================================================
    // O. SUBSCRIPTION-AWARE — intercept createForSubscriptionId() for dual-SIM
    // ==========================================================================

    private static void hookSubscriptionAware(ClassLoader cl) {
        // TelephonyManager.createForSubscriptionId(int) returns a new TelephonyManager
        // bound to a specific SIM slot. If we don't intercept it, the sub-specific
        // TM will bypass our hooks on the default TM's getter results.
        // Strategy: hook createForSubscriptionId itself, and then hook all relevant
        // getter methods on the returned TM's concrete class (if different from default).

        if (Build.VERSION.SDK_INT < 24) return;

        tryHook(() -> XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager", cl,
                "createForSubscriptionId", int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // The returned TelephonyManager is still android.telephony.TelephonyManager
                        // but bound to a specific sub. Since our getter hooks are on the class
                        // (not instance), they already apply. But we need to ensure
                        // listen()/registerTelephonyCallback() on the sub-TM also gets intercepted.
                        // Those are already hooked via class-level hooks, so nothing extra needed.
                        // However, log for debugging that a sub-TM was created.
                        Snapshot s = MainHook.CURRENT.get();
                        if (s.hasGsmCell() || s.hasLteCell() || s.hasNrCell()) {
                            XposedBridge.log(TAG + ": createForSubscriptionId(" + param.args[0]
                                    + ") intercepted — class-level hooks will apply");
                        }
                    }
                }));

        // SubscriptionManager.getActiveSubscriptionInfoList() — if spoofing cells,
        // force single-SIM appearance to prevent cross-slot leakage
        if (Build.VERSION.SDK_INT >= 22) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoList", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;

                            // Trim to first subscription only to prevent dual-SIM detection
                            Object result = param.getResult();
                            if (result instanceof List) {
                                List<?> subs = (List<?>) result;
                                if (subs.size() > 1) {
                                    ArrayList<Object> trimmed = new ArrayList<>();
                                    trimmed.add(subs.get(0));
                                    param.setResult(trimmed);
                                }
                            }
                        }
                    }));
        }

        // SubscriptionManager.getActiveSubscriptionInfoCount() — match trimmed list
        if (Build.VERSION.SDK_INT >= 22) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoCount", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                            int count = (int) param.getResult();
                            if (count > 1) {
                                param.setResult(1);
                            }
                        }
                    }));
        }

        // SubscriptionManager.getCompleteActiveSubscriptionInfoList() (API 30+)
        if (Build.VERSION.SDK_INT >= 30) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getCompleteActiveSubscriptionInfoList", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                            Object result = param.getResult();
                            if (result instanceof List) {
                                List<?> subs = (List<?>) result;
                                if (subs.size() > 1) {
                                    ArrayList<Object> trimmed = new ArrayList<>();
                                    trimmed.add(subs.get(0));
                                    param.setResult(trimmed);
                                }
                            }
                        }
                    }));
        }

        // SubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(int) (API 24+)
        // — return null for slot > 0 to hide second SIM
        if (Build.VERSION.SDK_INT >= 24) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoForSimSlotIndex", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                            int slotIndex = (int) param.args[0];
                            if (slotIndex > 0) {
                                param.setResult(null);
                            }
                        }
                    }));
        }

        // getDefaultDataSubscriptionId / getDefaultSmsSubscriptionId — passthrough is safe
        // because list/count/slot-index trimming already hides the second SIM from discovery.
        // No need to hook default sub IDs; they point to slot 0 by default on most devices.

        // SubscriptionManager.getActiveSubscriptionInfoCountMax() (API 22+)
        // — hardware slot count; cap to 1 to hide second SIM capability
        if (Build.VERSION.SDK_INT >= 22) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getActiveSubscriptionInfoCountMax", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                            int max = (int) param.getResult();
                            if (max > 1) {
                                param.setResult(1);
                            }
                        }
                    }));
        }

        // SubscriptionManager.getSubscriptionIds(int slotIndex) (API 34+)
        // — return empty array for slot > 0
        if (Build.VERSION.SDK_INT >= 34) {
            tryHook(() -> XposedHelpers.findAndHookMethod(
                    "android.telephony.SubscriptionManager", cl,
                    "getSubscriptionIds", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasGsmCell() && !s.hasLteCell() && !s.hasNrCell()) return;
                            int slotIndex = (int) param.args[0];
                            if (slotIndex > 0) {
                                param.setResult(new int[0]);
                            }
                        }
                    }));
        }
    }

    // ==========================================================================
    // P. FUSED LOCATION (Google Play Services)
    // ==========================================================================

    private static void hookFusedLocation(ClassLoader cl) {
        // FusedLocationProviderClient is in GMS; class may not exist on AOSP-only devices.
        // All hooks use tryHook to silently skip if GMS classes are absent.

        String fusedClient = "com.google.android.gms.location.FusedLocationProviderClient";
        String fusedApi = "com.google.android.gms.location.FusedLocationProviderApi";
        String locationResult = "com.google.android.gms.location.LocationResult";
        String locationCallback = "com.google.android.gms.location.LocationCallback";

        // FusedLocationProviderClient.getLastLocation() — returns Task<Location>
        tryHook(() -> XposedBridge.hookAllMethods(
                XposedHelpers.findClass(fusedClient, cl),
                "getLastLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        replaceFusedTask(param, s, cl);
                    }
                }));

        // FusedLocationProviderClient.getCurrentLocation(int, CancellationToken) — one-shot
        tryHook(() -> XposedBridge.hookAllMethods(
                XposedHelpers.findClass(fusedClient, cl),
                "getCurrentLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        replaceFusedTask(param, s, cl);
                    }
                }));

        // FusedLocationProviderClient.requestLocationUpdates — hook callback delivery
        // Covers both LocationCallback and LocationListener overloads.
        // PendingIntent overload is out of scope (requires intent broadcast interception).
        String gmsLocationListener = "com.google.android.gms.location.LocationListener";

        tryHook(() -> XposedBridge.hookAllMethods(
                XposedHelpers.findClass(fusedClient, cl),
                "requestLocationUpdates", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Try LocationCallback path
                        try {
                            Class<?> callbackBase = XposedHelpers.findClass(locationCallback, cl);
                            for (Object arg : param.args) {
                                if (arg != null && callbackBase.isInstance(arg)) {
                                    hookFusedLocationCallback(arg, cl);
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}

                        // Try GMS LocationListener path
                        try {
                            Class<?> listenerBase = XposedHelpers.findClass(gmsLocationListener, cl);
                            for (Object arg : param.args) {
                                if (arg != null && listenerBase.isInstance(arg)) {
                                    hookFusedLocationListener(arg, cl);
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }));

        // Legacy FusedLocationProviderApi (used via LocationServices.FusedLocationApi)
        tryHook(() -> {
            Class<?> apiClass = XposedHelpers.findClass(fusedApi, cl);
            XposedBridge.hookAllMethods(apiClass, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Snapshot s = MainHook.CURRENT.get();
                    if (!s.hasLocation()) return;
                    param.setResult(createFakeLocation(s));
                }
            });
        });

        // LocationResult.getLastLocation() / getLocations() — override at value object level
        tryHook(() -> {
            Class<?> lrClass = XposedHelpers.findClass(locationResult, cl);

            XposedBridge.hookAllMethods(lrClass, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Snapshot s = MainHook.CURRENT.get();
                    if (!s.hasLocation()) return;
                    param.setResult(createFakeLocation(s));
                }
            });

            XposedBridge.hookAllMethods(lrClass, "getLocations", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Snapshot s = MainHook.CURRENT.get();
                    if (!s.hasLocation()) return;
                    ArrayList<Location> fakeList = new ArrayList<>();
                    fakeList.add(createFakeLocation(s));
                    param.setResult(fakeList);
                }
            });
        });
    }

    /** Hook concrete LocationCallback subclass's onLocationResult(). Dedup via HOOKED set. */
    private static void hookFusedLocationCallback(Object callback, ClassLoader cl) {
        Class<?> cbClass = callback.getClass();
        String key = "flc#" + cbClass.getName() + "#onLocationResult";
        if (!HOOKED.add(key)) return;

        tryHook(() -> {
            Class<?> lrClass = XposedHelpers.findClass(
                    "com.google.android.gms.location.LocationResult", cl);
            XposedHelpers.findAndHookMethod(cbClass, "onLocationResult", lrClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Snapshot s = MainHook.CURRENT.get();
                            if (!s.hasLocation()) return;
                            // Replace LocationResult's internal location list
                            try {
                                ArrayList<Location> fakeList = new ArrayList<>();
                                fakeList.add(createFakeLocation(s));
                                Object lr = param.args[0];
                                XposedHelpers.setObjectField(lr, "mLocations", fakeList);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": FusedCallback.onLocationResult: " + t);
                            }
                        }
                    });
        });

        // Also hook onLocationAvailability if the callback overrides it
        String key2 = "flc#" + cbClass.getName() + "#onLocationAvailability";
        if (HOOKED.add(key2)) {
            tryHook(() -> {
                Class<?> laClass = XposedHelpers.findClass(
                        "com.google.android.gms.location.LocationAvailability", cl);
                XposedHelpers.findAndHookMethod(cbClass, "onLocationAvailability", laClass,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Snapshot s = MainHook.CURRENT.get();
                                if (!s.hasLocation()) return;
                                // Force location available = true
                                try {
                                    XposedHelpers.setBooleanField(param.args[0],
                                            "mIsLocationAvailable", true);
                                } catch (Throwable ignored) {}
                            }
                        });
            });
        }
    }

    /**
     * Replace Task<Location> result from fused APIs with a completed Task containing fake location.
     * Strategy: try Tasks.forResult() first (proper completed Task), fall back to mResult field.
     */
    private static void replaceFusedTask(XC_MethodHook.MethodHookParam param, Snapshot s, ClassLoader cl) {
        Location fake = createFakeLocation(s);
        // Strategy 1: Replace with a properly completed Task via Tasks.forResult()
        try {
            Class<?> tasksClass = XposedHelpers.findClass(
                    "com.google.android.gms.tasks.Tasks", cl);
            Object completedTask = XposedHelpers.callStaticMethod(tasksClass, "forResult", fake);
            param.setResult(completedTask);
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Tasks.forResult failed, trying field injection: " + t.getMessage());
        }
        // Strategy 2: Fallback — set internal result field on existing Task
        Object task = param.getResult();
        if (task != null) {
            try {
                XposedHelpers.setObjectField(task, "mResult", fake);
                // Also try to mark Task as complete so listeners fire
                try {
                    XposedHelpers.setBooleanField(task, "mComplete", true);
                    XposedHelpers.setIntField(task, "mResultSet", 1);
                } catch (Throwable ignored) {
                    // Field names vary across GMS versions
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Fused Task field injection failed: " + t);
            }
        }
    }

    /** Hook concrete GMS LocationListener subclass's onLocationChanged(). Dedup via HOOKED set. */
    private static void hookFusedLocationListener(Object listener, ClassLoader cl) {
        Class<?> listenerClass = listener.getClass();
        String key = "fll#" + listenerClass.getName() + "#onLocationChanged";
        if (!HOOKED.add(key)) return;

        tryHook(() -> XposedHelpers.findAndHookMethod(listenerClass,
                "onLocationChanged", Location.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Snapshot s = MainHook.CURRENT.get();
                        if (!s.hasLocation()) return;
                        param.args[0] = createFakeLocation(s);
                    }
                }));
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    /** Creates a fully populated fake Location from Snapshot. */
    private static Location createFakeLocation(Snapshot s) {
        Location l = new Location(LocationManager.GPS_PROVIDER);
        l.setLatitude(s.latitude);
        l.setLongitude(s.longitude);
        l.setAccuracy(s.accuracy != null ? s.accuracy : 10.0f);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        if (s.altitude != null) l.setAltitude(s.altitude);
        if (s.speed != null) l.setSpeed(s.speed);
        if (s.bearing != null) l.setBearing(s.bearing);

        return l;
    }

    /** Scan method args for a LocationListener instance (any position). */
    private static LocationListener findLocationListener(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof LocationListener) {
                return (LocationListener) arg;
            }
        }
        return null;
    }

    /**
     * Build CellInfo list from Snapshot cell identity fields.
     * Each CellInfo has BOTH CellIdentity AND CellSignalStrength set,
     * so apps traversing the list won't hit null on getCellSignalStrength().
     * Individual field values are further refined by getter hooks at read time.
     */
    @SuppressWarnings("unchecked")
    private static ArrayList buildCellInfoList(Snapshot s) {
        // Clear previous neighbor bypass set — fresh build each call
        NEIGHBOR_BYPASS.clear();
        ArrayList list = new ArrayList();
        int mcc = s.mcc != null ? s.mcc : 460;
        int mnc = s.mnc != null ? s.mnc : 0;

        // GSM cell
        if (s.hasGsmCell()) {
            try {
                CellInfoGsm gsm = (CellInfoGsm) XposedHelpers.newInstance(CellInfoGsm.class);
                Object identity;
                if (s.arfcn != null && s.bsic != null) {
                    identity = XposedHelpers.newInstance(CellIdentityGsm.class,
                            mcc, mnc, s.lac, s.cid, s.arfcn, s.bsic);
                } else {
                    identity = XposedHelpers.newInstance(CellIdentityGsm.class,
                            mcc, mnc, s.lac, s.cid);
                }
                XposedHelpers.callMethod(gsm, "setCellIdentity", identity);
                // Set signal strength — getter hooks will override individual fields
                try {
                    int rssi = s.gsmRssi != null ? s.gsmRssi : -85;
                    int ber = s.gsmBer != null ? s.gsmBer : 0;
                    int ta = s.gsmTa != null ? s.gsmTa : Integer.MAX_VALUE;
                    Object gsmSig = XposedHelpers.newInstance(
                            XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", null),
                            rssi, ber, ta);
                    XposedHelpers.callMethod(gsm, "setCellSignalStrength", gsmSig);
                } catch (Throwable sigErr) {
                    // Fallback: default-constructed signal (getter hooks still apply)
                    try {
                        Object gsmSig = XposedHelpers.newInstance(
                                XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", null));
                        XposedHelpers.callMethod(gsm, "setCellSignalStrength", gsmSig);
                    } catch (Throwable ignored) {}
                }
                list.add(gsm);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": GSM CellInfo creation failed: " + t);
            }
        }

        // LTE cell
        if (s.hasLteCell()) {
            try {
                CellInfoLte lte = (CellInfoLte) XposedHelpers.newInstance(CellInfoLte.class);
                int ci = s.ci;
                int pci = s.pci != null ? s.pci : 0;
                int tac = s.tac != null ? s.tac : (s.lac != null ? s.lac : 0);
                Object identity;
                if (s.earfcn != null) {
                    identity = XposedHelpers.newInstance(CellIdentityLte.class,
                            mcc, mnc, ci, pci, tac, s.earfcn);
                } else {
                    identity = XposedHelpers.newInstance(CellIdentityLte.class,
                            mcc, mnc, ci, pci, tac);
                }
                XposedHelpers.callMethod(lte, "setCellIdentity", identity);
                // Set signal strength
                try {
                    int rssi = s.lteRssi != null ? s.lteRssi : -90;
                    int rsrp = s.lteRsrp != null ? s.lteRsrp : -100;
                    int rsrq = s.lteRsrq != null ? s.lteRsrq : -10;
                    int sinr = s.lteSinr != null ? s.lteSinr : 15;
                    int cqi = s.lteCqi != null ? s.lteCqi : 10;
                    int lteTa = s.lteTa != null ? s.lteTa : Integer.MAX_VALUE;
                    Object lteSig = XposedHelpers.newInstance(
                            XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", null),
                            rssi, rsrp, rsrq, sinr, cqi, lteTa);
                    XposedHelpers.callMethod(lte, "setCellSignalStrength", lteSig);
                } catch (Throwable sigErr) {
                    try {
                        Object lteSig = XposedHelpers.newInstance(
                                XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", null));
                        XposedHelpers.callMethod(lte, "setCellSignalStrength", lteSig);
                    } catch (Throwable ignored) {}
                }
                list.add(lte);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": LTE CellInfo creation failed: " + t);
            }
        }

        // WCDMA cell — if we have PSC or basic cell identity
        if (s.hasGsmCell() && (s.psc != null || s.uarfcn != null)) {
            try {
                CellInfoWcdma wcdma = (CellInfoWcdma) XposedHelpers.newInstance(CellInfoWcdma.class);
                int psc = s.psc != null ? s.psc : 0;
                Object identity = XposedHelpers.newInstance(CellIdentityWcdma.class,
                        mcc, mnc, s.lac, s.cid, psc);
                XposedHelpers.callMethod(wcdma, "setCellIdentity", identity);
                // Set signal strength
                try {
                    Object wcdmaSig = XposedHelpers.newInstance(
                            XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", null));
                    XposedHelpers.callMethod(wcdma, "setCellSignalStrength", wcdmaSig);
                } catch (Throwable ignored) {}
                list.add(wcdma);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": WCDMA CellInfo creation failed: " + t);
            }
        }

        // NR/5G cell (API 29+)
        if (s.hasNrCell() && Build.VERSION.SDK_INT >= 29) {
            try {
                Class<?> cellInfoNrClass = XposedHelpers.findClass("android.telephony.CellInfoNr", null);
                Class<?> cellIdNrClass = XposedHelpers.findClass("android.telephony.CellIdentityNr", null);
                Class<?> cellSigNrClass = XposedHelpers.findClass("android.telephony.CellSignalStrengthNr", null);

                Object nr = XposedHelpers.newInstance(cellInfoNrClass);

                // Set CellIdentityNr via internal field (no public setter on CellInfoNr)
                Object nrIdentity = XposedHelpers.newInstance(cellIdNrClass);
                XposedHelpers.setObjectField(nr, "mCellIdentity", nrIdentity);

                // Set CellSignalStrengthNr
                Object nrSignal = XposedHelpers.newInstance(cellSigNrClass);
                XposedHelpers.setObjectField(nr, "mCellSignalStrength", nrSignal);

                // Individual fields overridden by CellIdentityNr/CellSignalStrengthNr getter hooks
                list.add(nr);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": NR CellInfo creation failed: " + t);
            }
        }

        // Neighbor cells from JSON
        // Format: [{"type":"gsm","mcc":460,"mnc":0,"lac":1234,"cid":5678,"rssi":-85}, ...]
        // Supported types: gsm (rssi,ber,ta), lte (rssi,rsrp,rsrq,sinr,cqi,ta), wcdma (rssi,rscp,ecno)
        if (s.neighborCellsJson != null && !s.neighborCellsJson.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(s.neighborCellsJson);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        org.json.JSONObject obj = arr.getJSONObject(i);
                        String type = obj.optString("type", "gsm");
                        int nMcc = obj.optInt("mcc", mcc);
                        int nMnc = obj.optInt("mnc", mnc);
                        if ("lte".equals(type)) {
                            CellInfoLte lte = (CellInfoLte) XposedHelpers.newInstance(CellInfoLte.class);
                            Object id = XposedHelpers.newInstance(CellIdentityLte.class,
                                    nMcc, nMnc,
                                    obj.optInt("ci", 0),
                                    obj.optInt("pci", 0),
                                    obj.optInt("tac", 0));
                            NEIGHBOR_BYPASS.add(id);
                            XposedHelpers.callMethod(lte, "setCellIdentity", id);
                            try {
                                Object sig = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", null),
                                        obj.optInt("rssi", -90),
                                        obj.optInt("rsrp", -100),
                                        obj.optInt("rsrq", -10),
                                        obj.optInt("sinr", 15),
                                        obj.optInt("cqi", Integer.MAX_VALUE),
                                        obj.optInt("ta", Integer.MAX_VALUE));
                                NEIGHBOR_BYPASS.add(sig);
                                XposedHelpers.callMethod(lte, "setCellSignalStrength", sig);
                            } catch (Throwable ignored) {}
                            list.add(lte);
                        } else if ("wcdma".equals(type)) {
                            CellInfoWcdma w = (CellInfoWcdma) XposedHelpers.newInstance(CellInfoWcdma.class);
                            Object id = XposedHelpers.newInstance(CellIdentityWcdma.class,
                                    nMcc, nMnc,
                                    obj.optInt("lac", 0),
                                    obj.optInt("cid", 0),
                                    obj.optInt("psc", 0));
                            NEIGHBOR_BYPASS.add(id);
                            XposedHelpers.callMethod(w, "setCellIdentity", id);
                            try {
                                Object sig = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("android.telephony.CellSignalStrengthWcdma", null),
                                        obj.optInt("rssi", -85),
                                        Integer.MAX_VALUE, // ber
                                        obj.optInt("rscp", -100),
                                        obj.optInt("ecno", -10));
                                NEIGHBOR_BYPASS.add(sig);
                                XposedHelpers.callMethod(w, "setCellSignalStrength", sig);
                            } catch (Throwable ignored) {}
                            list.add(w);
                        } else {
                            // Default: GSM
                            CellInfoGsm gsm = (CellInfoGsm) XposedHelpers.newInstance(CellInfoGsm.class);
                            Object id = XposedHelpers.newInstance(CellIdentityGsm.class,
                                    nMcc, nMnc,
                                    obj.optInt("lac", 0),
                                    obj.optInt("cid", 0));
                            NEIGHBOR_BYPASS.add(id);
                            XposedHelpers.callMethod(gsm, "setCellIdentity", id);
                            try {
                                Object sig = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", null),
                                        obj.optInt("rssi", -85),
                                        obj.optInt("ber", 0),
                                        obj.optInt("ta", Integer.MAX_VALUE));
                                NEIGHBOR_BYPASS.add(sig);
                                XposedHelpers.callMethod(gsm, "setCellSignalStrength", sig);
                            } catch (Throwable ignored) {}
                            list.add(gsm);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Neighbor cell " + i + " creation failed: " + t);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Neighbor cells JSON parse failed: " + t);
            }
        }

        return list;
    }

    /**
     * Hook a no-arg getter method. If the Snapshot field is non-null, override the return value.
     * Silently skips if the method doesn't exist on this Android version.
     */
    private static void hookGetter(ClassLoader cl, String className, String methodName,
                                   FieldGetter getter) {
        tryHook(() -> XposedHelpers.findAndHookMethod(className, cl, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Skip neighbor cell objects — their constructor values are correct
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) return;
                        Snapshot s = MainHook.CURRENT.get();
                        Object val = getter.get(s);
                        if (val != null) {
                            param.setResult(val);
                        }
                    }
                }));
    }

    /**
     * Hook a signal strength getter. Same as hookGetter but applies fluctuation.
     * Skips neighbor cell objects (NEIGHBOR_BYPASS).
     */
    private static void hookSignal(ClassLoader cl, String className, String methodName,
                                   FieldGetter getter) {
        tryHook(() -> XposedHelpers.findAndHookMethod(className, cl, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (NEIGHBOR_BYPASS.contains(param.thisObject)) return;
                        Snapshot s = MainHook.CURRENT.get();
                        Object val = getter.get(s);
                        if (val instanceof Integer) {
                            param.setResult(s.fluctuate((Integer) val, RND));
                        }
                    }
                }));
    }

    /** Try to run a hook registration; log and continue on failure. */
    private static void tryHook(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Hook registration skipped: " + t.getMessage());
        }
    }

    /** Convert dotted IPv4 string to int (little-endian for Android WifiInfo). */
    private static int ipToInt(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return 0;
            return (Integer.parseInt(parts[0]))
                    | (Integer.parseInt(parts[1]) << 8)
                    | (Integer.parseInt(parts[2]) << 16)
                    | (Integer.parseInt(parts[3]) << 24);
        } catch (Exception e) {
            return 0;
        }
    }

    @FunctionalInterface
    interface FieldGetter {
        Object get(Snapshot s);
    }
}
