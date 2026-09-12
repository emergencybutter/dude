package nyc.curbside.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetectionCard(state, viewModel)
        SharingCard(state, viewModel)
        DataCard(state, viewModel)
    }
}

@Composable
private fun DetectionCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How your car is detected", style = MaterialTheme.typography.titleMedium)

            SignalRow(
                title = "Motion sensing",
                detail = if (state.transitionsRegistered) {
                    "Active. Runs on your phone's motion coprocessor; works with Curbside closed."
                } else {
                    "Not available. Grant the physical activity permission to enable it."
                },
                healthy = state.transitionsRegistered,
            )

            SignalRow(
                title = "Android Auto",
                detail = if (state.androidAutoSeen) {
                    "Seen on this phone. The sharpest signal Curbside has — when you unplug, it knows."
                } else {
                    "Not seen yet. It will be used automatically the first time you plug in."
                },
                healthy = state.androidAutoSeen,
            )

            SignalRow(
                title = "Car stereo",
                detail = state.carBluetoothName
                    ?.let { "Paired with $it. Disconnecting counts as parking." }
                    ?: "No stereo chosen. Pick one so Curbside can tell your car from your headphones.",
                healthy = state.carBluetoothName != null,
            )

            OutlinedButton(onClick = viewModel::onChooseCarStereo) { Text("Choose car stereo") }

            Text(
                "None of these keeps GPS running. Curbside asks for a location exactly once per " +
                    "drive, at the moment it decides you have parked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SharingCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sharing", style = MaterialTheme.typography.titleMedium)

            if (state.householdId == null) {
                Text(
                    "Pair a second phone to share parking spots automatically. Locations are " +
                        "encrypted on this device with a key that only your two phones hold.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::onCreateHousehold) { Text("Show pairing code") }
                    OutlinedButton(onClick = viewModel::onScanPairing) { Text("Scan a code") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Share every spot automatically", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Off by default. You can still share any single spot from the car card.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = state.autoShare, onCheckedChange = viewModel::onAutoShareChanged)
                }
                OutlinedButton(onClick = viewModel::onLeaveHousehold) { Text("Leave household") }
            }
        }
    }
}

@Composable
private fun DataCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Street data", style = MaterialTheme.typography.titleMedium)
            Text(
                state.datasetVersion?.let { "Sign data version $it, ${state.segmentCount} curbs." }
                    ?: "Sign data not installed yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                state.suspensionSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedButton(onClick = viewModel::onRefreshData) { Text("Check for updates") }
        }
    }
}

@Composable
private fun SignalRow(title: String, detail: String, healthy: Boolean) {
    Column {
        Text(
            text = if (healthy) "✓ $title" else "· $title",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
