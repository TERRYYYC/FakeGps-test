package name.caiyao.fakegps.mockprovider

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

class AndroidMockProviderGateway(
    private val locationManager: LocationManager,
    private val sampleFactory: MockLocationSampleFactory = MockLocationSampleFactory(
        elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
    ),
) : MockProviderGateway {

    override fun replaceGpsProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addModernGpsProvider()
        else addLegacyGpsProvider()
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    override fun publish(config: MockLocationConfig) {
        val sample = sampleFactory.create(config)
        val location = Location(sample.provider).apply {
            latitude = sample.latitude
            longitude = sample.longitude
            sample.altitudeMeters?.let { altitude = it }
            accuracy = sample.accuracyMeters
            time = sample.timeMillis
            elapsedRealtimeNanos = sample.elapsedRealtimeNanos
            speed = 0f
            bearing = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 1f
                speedAccuracyMetersPerSecond = 0.1f
                bearingAccuracyDegrees = 0.1f
            }
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
    }

    override fun removeGpsProvider() {
        locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun addModernGpsProvider() {
        val properties = ProviderProperties.Builder()
            .setHasNetworkRequirement(false)
            .setHasSatelliteRequirement(false)
            .setHasCellRequirement(false)
            .setHasMonetaryCost(false)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()
        locationManager.addTestProvider(LocationManager.GPS_PROVIDER, properties)
    }

    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun addLegacyGpsProvider() {
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false,
            false,
            false,
            false,
            true,
            true,
            true,
            ProviderProperties.POWER_USAGE_HIGH,
            ProviderProperties.ACCURACY_FINE,
        )
    }
}
