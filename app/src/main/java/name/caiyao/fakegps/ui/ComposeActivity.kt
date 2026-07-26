package name.caiyao.fakegps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import name.caiyao.fakegps.ui.navigation.AppNavGraph
import name.caiyao.fakegps.ui.theme.FakeGpsTheme

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Publish the effective spoof config into world-readable prefs so the Xposed hook
        // (running inside target apps, e.g. Google Maps) can read it via XSharedPreferences.
        // This is the REAL launcher entry point; the legacy SplashActivity is never opened.
        name.caiyao.fakegps.config.ConfigPrefsSync.sync(applicationContext)

        // Read-back probe: logs what this (hooked) process actually observes through the public
        // Android APIs, so scripts/test-hook.sh can assert the whole chain with no manual taps.
        //
        // Deliberately delayed: the hook loads its config on a timer ~3s after process start (the
        // very first load runs before Application exists). Probing in onCreate raced that and read
        // pre-spoof values, which looked exactly like a broken hook.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            name.caiyao.fakegps.probe.HookProbe.run(this)
        }, 5000)

        enableEdgeToEdge()
        setContent {
            FakeGpsTheme {
                val navController = rememberNavController()
                AppNavGraph(navController = navController)
            }
        }
    }
}
