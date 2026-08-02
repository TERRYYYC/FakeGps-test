package name.caiyao.fakegps.mockprovider

import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock

class AndroidMockProviderGateway(
    private val locationManager: LocationManager,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val sampleFactory: MockLocationSampleFactory = MockLocationSampleFactory(
        elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
    ),
) : MockProviderGateway {

    override fun replaceGpsProvider() {
        when (ProviderApiFamily.forSdk(sdkInt)) {
            ProviderApiFamily.Modern -> addModernGpsProvider()
            ProviderApiFamily.Legacy -> addLegacyGpsProvider()
        }
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    override fun publish(config: MockLocationConfig) {
        val sample = sampleFactory.create(config)
        val location = Location(sample.provider).apply {
            latitude = sample.latitude
            longitude = sample.longitude
            altitude = 3.0
            accuracy = sample.accuracyMeters
            time = sample.timeMillis
            elapsedRealtimeNanos = sample.elapsedRealtimeNanos
            speed = 0f
            bearing = 0f
            if (sdkInt >= Build.VERSION_CODES.O) {
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
