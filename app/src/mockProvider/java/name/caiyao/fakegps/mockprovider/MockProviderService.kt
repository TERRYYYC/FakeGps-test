package name.caiyao.fakegps.mockprovider

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import name.caiyao.fakegps.R

class MockProviderService : Service() {
    private lateinit var controller: MockProviderSessionController
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            controller.tick()
            publishState("tick")
            if (controller.state is MockProviderState.Running) {
                handler.postDelayed(this, TICK_MILLIS)
            } else {
                finishService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        controller = MockProviderSessionController(AndroidMockProviderGateway(manager))
    }

    @RequiresPermission(Manifest.permission.FOREGROUND_SERVICE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = MockProviderServiceContract.decode(
            action = intent?.action,
            latitude = intent?.optionalDouble(MockProviderServiceContract.EXTRA_LATITUDE),
            longitude = intent?.optionalDouble(MockProviderServiceContract.EXTRA_LONGITUDE),
            accuracyMeters = intent?.getFloatExtra(
                MockProviderServiceContract.EXTRA_ACCURACY_METERS,
                MockProviderServiceContract.DEFAULT_ACCURACY_METERS,
            ) ?: MockProviderServiceContract.DEFAULT_ACCURACY_METERS,
        )
        when (command) {
            is MockProviderCommand.Start -> startSession(command.config)
            MockProviderCommand.Stop,
            MockProviderCommand.StopAfterProcessRecreation,
            -> stopSession()
            is MockProviderCommand.Rejected -> {
                MockProviderStatusStore.publish(MockProviderState.Failed(command.message))
                Log.e(TAG, "rejected pid=${Process.myPid()} reason=${command.message}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::controller.isInitialized && controller.state !is MockProviderState.Idle) {
            controller.stop()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.FOREGROUND_SERVICE)
    private fun startSession(config: MockLocationConfig) {
        handler.removeCallbacks(tick)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting system GPS test provider"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        controller.start(config)
        publishState("start")
        if (controller.state is MockProviderState.Running) {
            handler.postDelayed(tick, TICK_MILLIS)
        } else {
            finishService()
        }
    }

    private fun stopSession() {
        handler.removeCallbacks(tick)
        controller.stop()
        publishState("stop")
        finishService()
    }

    private fun publishState(event: String) {
        val state = controller.state
        MockProviderStatusStore.publish(state)
        Log.i(TAG, "event=$event pid=${Process.myPid()} state=$state")
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mock Provider lab",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_lan)
        .setContentTitle("FakeGPS Mock Provider Lab")
        .setContentText(text)
        .setOngoing(true)
        .addAction(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, MockProviderService::class.java)
                    .setAction(MockProviderServiceContract.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun Intent.optionalDouble(name: String): Double? {
        if (!hasExtra(name)) return null
        getStringExtra(name)?.toDoubleOrNull()?.let { return it }
        return getDoubleExtra(name, Double.NaN).takeIf(Double::isFinite)
    }

    companion object {
        private const val TAG = "MockProviderLab"
        private const val CHANNEL_ID = "mock_provider_lab"
        private const val NOTIFICATION_ID = 2401
        private const val TICK_MILLIS = 1_000L
    }
}
