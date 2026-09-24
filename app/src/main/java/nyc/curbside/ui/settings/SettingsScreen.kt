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
import androidx.compose.foundation.layout.size
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
import nyc.curbside.share.PairingInvite
import nyc.curbside.ui.CarStereoChooser
import nyc.curbside.ui.hasBluetoothPermission
import nyc.curbside.ui.carStereos
import nyc.curbside.ui.BLUETOOTH_PERMISSION
import nyc.curbside.ui.share.QrCode
import nyc.curbside.ui.share.QrScannerDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onReviewPermissions: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetectionCard(state, viewModel, onReviewPermissions)
        SharingCard(state, viewModel)
        DataCard(state, viewModel)
    }
}

@Composable
private fun DetectionCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onReviewPermissions: () -> Unit,
) {
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

            OutlinedButton(onClick = onReviewPermissions) { Text("Review permissions") }

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
private fun CarStereoPicker(state: SettingsUiState, viewModel: SettingsViewModel) {
    // One row per car, because a household can have more than one and the whole point of naming
    // them is knowing which one your partner just moved.
    state.vehicles.forEach { vehicle ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(vehicle.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (vehicle.isShareable) {
                        "Recognised by its stereo"
                    } else {
                        "No stereo — this car cannot be matched with your partner's phone"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            TextButton(onClick = { viewModel.onVehicleRemoved(vehicle.id) }) { Text("Forget") }
        }
    }

    // Finding beats asking: a car stereo declares itself as car audio, so the usual case needs no
    // list at all. The manual picker stays for the head units that declare themselves badly.
    val context = LocalContext.current
    var searched by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            carStereos(context).forEach { viewModel.onVehicleAdded(it.address, it.label) }
            searched = true
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (hasBluetoothPermission(context)) {
                    carStereos(context).forEach { viewModel.onVehicleAdded(it.address, it.label) }
                    searched = true
                } else {
                    request.launch(BLUETOOTH_PERMISSION)
                }
            },
        ) { Text("Find my car") }

        CarStereoChooser(onChosen = viewModel::onVehicleAdded) { open ->
            OutlinedButton(onClick = open) {
                Text(if (state.vehicles.isEmpty()) "Add by hand" else "Add another")
            }
        }
    }

    if (searched && state.vehicles.isEmpty()) {
        Text(
            "Nothing among your paired devices says it is a car. Plenty of head units do not " +
                "declare themselves properly — add yours by hand.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
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
            } else if (state.pendingInvite != null) {
                // Creating the household is what puts this device in one, so the code has to be
                // shown from its own branch: by the time it exists, householdId is already set and
                // the joined layout below would otherwise have replaced the button that asked
                // for it.
                PairingCode(state.pendingInvite, onDone = viewModel::onDismissPairingCode)
            } else if (state.householdId == null) {
                Text(
                    "Pair a second phone to share parking spots automatically. Locations are " +
                        "encrypted on this device with a key that only your two phones hold.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::onShowPairingCode,
                        enabled = !state.busy,
                    ) { Text("Show pairing code") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Still offered once paired: a third device, or a replacement phone, joins the
                    // same household rather than starting another one.
                    OutlinedButton(
                        onClick = viewModel::onShowPairingCode,
                        enabled = !state.busy,
                    ) { Text("Add another phone") }
                    OutlinedButton(onClick = viewModel::onLeaveHousehold) { Text("Leave household") }
                }
            }

            state.pairingError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (state.scanning) {
        QrScannerDialog(
            onScanned = viewModel::onPairingScanned,
            onDismiss = viewModel::onScannerDismissed,
        )
    }
}

@Composable
private fun PairingCode(invite: PairingInvite, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "On the other phone, open Curbside › Settings › Sharing and tap “Scan a code”.",
            style = MaterialTheme.typography.bodyMedium,
        )

        QrCode(invite.toQrPayload(), Modifier.size(240.dp))

        Text(
            "Good once, for ${minutesLeft(invite.expiresAtEpochMillis)}. The square carries the " +
                "key that decrypts your spots — it never reaches our server, so keep it out of " +
                "screenshots and group chats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        OutlinedButton(onClick = onDone) { Text("Done") }
    }
}

private fun minutesLeft(expiresAtEpochMillis: Long): String {
    val minutes = (expiresAtEpochMillis - System.currentTimeMillis()) / 60_000L
    return when {
        minutes >= 2L -> "$minutes minutes"
        minutes >= 1L -> "another minute"
        else -> "a few more seconds"
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
