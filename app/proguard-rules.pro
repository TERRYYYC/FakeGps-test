# FakeGPS ProGuard rules

# Xposed hook entry point and supporting classes - must not be obfuscated
-keep class name.caiyao.fakegps.hook.MainHook
-keep class name.caiyao.fakegps.hook.HookUtils
-keep class name.caiyao.fakegps.hook.Snapshot

# Keep Xposed-related classes
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# OSMDroid
-dontwarn org.osmdroid.**
