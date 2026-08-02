package name.caiyao.fakegps.mockprovider

data class MockProviderForm(
    val latitude: String,
    val longitude: String,
    val accuracyMeters: String = "3",
) {
    fun validate(): MockProviderFormResult {
        val lat = latitude.trim().toDoubleOrNull()
            ?: return MockProviderFormResult.Invalid("Latitude must be a number")
        val lon = longitude.trim().toDoubleOrNull()
            ?: return MockProviderFormResult.Invalid("Longitude must be a number")
        val accuracy = accuracyMeters.trim().toFloatOrNull()
            ?: return MockProviderFormResult.Invalid("accuracy must be a number")
        return runCatching { MockLocationConfig(lat, lon, accuracy) }.fold(
            onSuccess = MockProviderFormResult::Valid,
            onFailure = {
                MockProviderFormResult.Invalid(it.message ?: "Invalid mock location")
            },
        )
    }
}

sealed interface MockProviderFormResult {
    data class Valid(val config: MockLocationConfig) : MockProviderFormResult
    data class Invalid(val message: String) : MockProviderFormResult
}

object MockProviderUiCopy {
    const val TITLE = "FakeGPS Mock Provider Lab"
    const val PERMISSION_GUIDANCE =
        "Developer options → Select mock location app → FakeGPS Mock Provider Lab"
    const val RESTORE_GUIDANCE =
        "After testing, stop this lab and select Fake GPS Location again."

    fun status(state: MockProviderState): String = when (state) {
        MockProviderState.Idle -> "Idle"
        is MockProviderState.Starting -> "Starting"
        is MockProviderState.Running -> "Running · ${state.emittedCount} samples"
        MockProviderState.Stopping -> "Stopping"
        is MockProviderState.Failed -> "Failed · ${state.message}"
    }
}
