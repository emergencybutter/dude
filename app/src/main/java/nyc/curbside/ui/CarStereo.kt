package nyc.curbside.ui

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * How sure we are that a paired device is a car, from what Bluetooth says about itself.
 *
 * Nothing here is a name match. Devices declare a minor class, and there is a value reserved for
 * car audio that headphones do not use; car kits also advertise phonebook access, because they pull
 * your contacts onto the dash, which headphones have no reason to do.
 */
enum class CarLikelihood { CAR, MAYBE, OTHER }

/** A paired device, read once when the dialog opens. */
data class PairedDevice(
    val address: String,
    val label: String,
    val audio: Boolean,
    val likelihood: CarLikelihood = CarLikelihood.OTHER,
)

/** Phonebook Access Profile. A car wants your contacts; a pair of earbuds does not. */
private val PBAP_SERVER: UUID = UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB")
private val MAP_SERVER: UUID = UUID.fromString("00001132-0000-1000-8000-00805F9B34FB")

/**
 * BLUETOOTH_CONNECT is needed to read so much as the name of a paired device on API 31+, so the
 * caller asks for it and only then opens the list. Below 31 the install-time BLUETOOTH permission
 * covers it and the request resolves immediately.
 */
val BLUETOOTH_PERMISSION: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

fun hasBluetoothPermission(context: Context): Boolean = BLUETOOTH_PERMISSION.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission") // Only called behind hasBluetoothPermission.
fun pairedDevices(context: Context): List<PairedDevice> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    adapter.bondedDevices.orEmpty()
        .map {
            PairedDevice(
                address = it.address,
                label = it.name?.takeIf(String::isNotBlank) ?: it.address,
                audio = it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO,
                likelihood = likelihoodOf(
                    it.bluetoothClass?.deviceClass,
                    it.uuids.orEmpty().mapNotNull { parcel -> parcel?.uuid },
                ),
            )
        }
        // Audio devices first, since one of them is the answer, but nothing is hidden: plenty of
        // head units report an odd device class, and a list that omits the user's car is useless.
        .sortedWith(compareByDescending<PairedDevice> { it.audio }.thenBy { it.label.lowercase() })
}.getOrDefault(emptyList())

/**
 * What a device's own Bluetooth class says it is.
 *
 * [BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO] is reserved for car audio and is the one honest
 * answer available: no headphone reports it. Hands-free is weaker on its own — speakerphones use it
 * too — so it only counts as a car alongside phonebook or message access, which is a dashboard
 * wanting your contacts.
 *
 * Plenty of head units are lazy and report nothing useful, so a manual picker has to remain.
 */
internal fun likelihoodOf(deviceClass: Int?, services: List<UUID>): CarLikelihood {
    val phonebook = services.any { it == PBAP_SERVER || it == MAP_SERVER }
    return when (deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> CarLikelihood.CAR
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ->
            if (phonebook) CarLikelihood.CAR else CarLikelihood.MAYBE
        else -> CarLikelihood.OTHER
    }
}

/** Every paired device that says it is a car, for setting the household up without a list. */
fun carStereos(context: Context): List<PairedDevice> =
    pairedDevices(context).filter { it.likelihood == CarLikelihood.CAR }

/**
 * Asks the Bluetooth permission, then puts the paired devices in a dialog.
 *
 * Shared by onboarding and Settings. Nominating a stereo is the difference between detection that
 * knows when the ignition went off and detection guessing from motion alone, so it is offered while
 * the user is still setting the app up rather than only to whoever goes looking in Settings.
 *
 * @param content the button, given a lambda that opens the list.
 */
@Composable
fun CarStereoChooser(
    onChosen: (address: String, label: String) -> Unit,
    content: @Composable (open: () -> Unit) -> Unit,
) {
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

    content {
        denied = false
        if (hasBluetoothPermission(context)) {
            devices = pairedDevices(context)
        } else {
            request.launch(BLUETOOTH_PERMISSION)
        }
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
        title = { Text("Which stereo is this car?") },
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
                            "disconnecting as the end of a drive. It is also how the car is told " +
                            "apart from the other one, on both your phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    paired.forEach { device ->
                        Text(
                            if (device.likelihood == CarLikelihood.OTHER) {
                                device.label
                            } else {
                                "${device.label}  ·  looks like a car"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChosen(device.address, device.label)
                                    devices = null
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { devices = null }) { Text("Cancel") } },
    )
}
