package name.caiyao.fakegps.mockprovider

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import name.caiyao.fakegps.ui.theme.FakeGpsTheme

class MockProviderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FakeGpsTheme {
                val providerState by MockProviderStatusStore.state.collectAsStateWithLifecycle()
                var pendingConfig by remember { mutableStateOf<MockLocationConfig?>(null) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    val config = pendingConfig
                    pendingConfig = null
                    if (fineGranted && config != null) startProvider(config)
                }

                MockProviderScreen(
                    providerState = providerState,
                    onOpenDeveloperOptions = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                        )
                    },
                    onStart = { config ->
                        val missing = requiredRuntimePermissions().filterNot(::hasPermission)
                        if (missing.isEmpty()) {
                            startProvider(config)
                        } else {
                            pendingConfig = config
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    },
                    onStop = ::stopProvider,
                )
            }
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startProvider(config: MockLocationConfig) {
        val intent = Intent(this, MockProviderService::class.java)
            .setAction(MockProviderServiceContract.ACTION_START)
            .putExtra(MockProviderServiceContract.EXTRA_LATITUDE, config.latitude)
            .putExtra(MockProviderServiceContract.EXTRA_LONGITUDE, config.longitude)
            .putExtra(MockProviderServiceContract.EXTRA_ACCURACY_METERS, config.accuracyMeters)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopProvider() {
        startService(
            Intent(this, MockProviderService::class.java)
                .setAction(MockProviderServiceContract.ACTION_STOP),
        )
    }
}
