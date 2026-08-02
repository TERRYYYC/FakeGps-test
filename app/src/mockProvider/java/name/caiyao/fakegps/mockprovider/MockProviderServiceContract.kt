package name.caiyao.fakegps.mockprovider

sealed interface MockProviderCommand {
    data class Start(val config: MockLocationConfig) : MockProviderCommand
    data object Stop : MockProviderCommand
    data object StopAfterProcessRecreation : MockProviderCommand
    data class Rejected(val message: String) : MockProviderCommand
}

object MockProviderServiceContract {
    const val ACTION_START = "name.caiyao.fakegps.mockprovider.action.START"
    const val ACTION_STOP = "name.caiyao.fakegps.mockprovider.action.STOP"
    const val EXTRA_LATITUDE = "latitude"
    const val EXTRA_LONGITUDE = "longitude"
    const val EXTRA_ACCURACY_METERS = "accuracy_meters"
    const val DEFAULT_ACCURACY_METERS = 3f

    fun decode(
        action: String?,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float = DEFAULT_ACCURACY_METERS,
    ): MockProviderCommand = when (action) {
        null -> MockProviderCommand.StopAfterProcessRecreation
        ACTION_STOP -> MockProviderCommand.Stop
        ACTION_START -> {
            if (latitude == null || longitude == null) {
                MockProviderCommand.Rejected("start requires latitude and longitude")
            } else {
                runCatching {
                    MockLocationConfig(latitude, longitude, accuracyMeters)
                }.fold(
                    onSuccess = MockProviderCommand::Start,
                    onFailure = {
                        MockProviderCommand.Rejected(
                            it.message ?: "invalid mock location",
                        )
                    },
                )
            }
        }
        else -> MockProviderCommand.Rejected("unknown service action: $action")
    }
}

enum class ProviderApiFamily {
    Legacy,
    Modern;

    companion object {
        fun forSdk(sdkInt: Int): ProviderApiFamily =
            if (sdkInt >= 31) Modern else Legacy
    }
}
