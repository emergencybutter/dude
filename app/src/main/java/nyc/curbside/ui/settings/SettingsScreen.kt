package nyc.curbside.ui.settings

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

            CarStereoPicker(state, viewModel)

            Text(
                "None of these keeps GPS running. Curbside asks for a location exactly once per " +
                    "drive, at the moment it decides you have parked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** A paired device, read once when the dialog opens. */
private data class PairedDevice(val address: String, val label: String, val audio: Boolean)

/**
 * Connecting to BLUETOOTH_CONNECT is needed to read so much as the name of a paired device on
 * API 31+, so the button asks for it and only then opens the list. Below 31 the install-time
 * BLUETOOTH permission covers it and the request resolves immediately.
 */
private val BLUETOOTH_PERMISSION: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

private fun hasBluetoothPermission(context: Context): Boolean = BLUETOOTH_PERMISSION.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun pairedDevices(context: Context): List<PairedDevice> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    adapter.bondedDevices.orEmpty()
        .map {
            PairedDevice(
                address = it.address,
                label = it.name?.takeIf(String::isNotBlank) ?: it.address,
                audio = it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO,
            )
        }
        // Audio devices first, since one of them is the answer, but nothing is hidden: plenty of
        // head units report an odd device class, and a list that omits the user's car is useless.
        .sortedWith(compareByDescending<PairedDevice> { it.audio }.thenBy { it.label.lowercase() })
}.getOrDefault(emptyList())

@Composable
private fun CarStereoPicker(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<PairedDevice>?>(null) }
    var denied by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            devices = pairedDevices(context)
            denied = false
        } else {
            denied = true
        }
    }

    OutlinedButton(
        onClick = {
            denied = false
            if (hasBluetoothPermission(context)) {
                devices = pairedDevices(context)
            } else {
                request.launch(BLUETOOTH_PERMISSION)
            }
        },
    ) {
        Text(if (state.carBluetoothName == null) "Choose car stereo" else "Change car stereo")
    }

    if (denied) {
        Text(
            "Curbside needs the Bluetooth permission to read the names of your paired devices. " +
                "Without it, drives are detected by motion alone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val paired = devices ?: return
    AlertDialog(
        onDismissRequest = { devices = null },
        title = { Text("Which one is your car?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (paired.isEmpty()) {
                    Text(
                        "No paired Bluetooth devices. Pair your phone with the car stereo first, " +
                            "then come back.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "Pick the stereo, not your headphones: Curbside treats this device " +
                            "disconnecting as the end of a drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    paired.forEach { device ->
                        Text(
                            device.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onCarStereoChosen(device.address, device.label)
                                    devices = null
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { devices = null }) { Text("Cancel") }
        },
        dismissButton = {
            if (state.carBluetoothName != null) {
                TextButton(
                    onClick = {
                        viewModel.onCarStereoChosen(null, null)
                        devices = null
                    },
                ) { Text("Forget") }
            }
        },
    )
}

@Composable
private fun SharingCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sharing", style = MaterialTheme.typography.titleMedium)

            if (!state.sharingAvailable) {
                Text(
                    "Unavailable in this build. Sharing needs a Firebase project, and this copy of " +
                        "Curbside was built without one. Everything else works.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else if (state.householdId == null) {
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
