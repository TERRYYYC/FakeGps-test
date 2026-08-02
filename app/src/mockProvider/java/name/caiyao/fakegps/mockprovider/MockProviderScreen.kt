package name.caiyao.fakegps.mockprovider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockProviderScreen(
    providerState: MockProviderState,
    onOpenDeveloperOptions: () -> Unit,
    onStart: (MockLocationConfig) -> Unit,
    onStop: () -> Unit,
) {
    var latitude by remember { mutableStateOf("40.7128") }
    var longitude by remember { mutableStateOf("-74.0060") }
    var accuracy by remember { mutableStateOf("3") }
    var formError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(MockProviderUiCopy.TITLE) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "System GPS test-provider experiment",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Package: name.caiyao.fakegps.mockprovider\n" +
                    "This app is separate from FakeGps and Fake GPS Location.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Status", style = MaterialTheme.typography.labelLarge)
                    Text(MockProviderUiCopy.status(providerState))
                }
            }

            Text(MockProviderUiCopy.PERMISSION_GUIDANCE)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenDeveloperOptions,
            ) {
                Text("Open Developer options")
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = latitude,
                onValueChange = { latitude = it; formError = null },
                label = { Text("Latitude") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = longitude,
                onValueChange = { longitude = it; formError = null },
                label = { Text("Longitude") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = accuracy,
                onValueChange = { accuracy = it; formError = null },
                label = { Text("Accuracy (meters)") },
                singleLine = true,
            )

            formError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        when (
                            val result = MockProviderForm(
                                latitude = latitude,
                                longitude = longitude,
                                accuracyMeters = accuracy,
                            ).validate()
                        ) {
                            is MockProviderFormResult.Valid -> onStart(result.config)
                            is MockProviderFormResult.Invalid -> formError = result.message
                        }
                    },
                ) {
                    Text("Start")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onStop,
                ) {
                    Text("Stop")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Restore before leaving", style = MaterialTheme.typography.labelLarge)
                    Text(MockProviderUiCopy.RESTORE_GUIDANCE)
                    Text("Only one mock-location app can be selected at a time.")
                }
            }
        }
    }
}
