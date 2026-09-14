package nyc.curbside.ui.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import nyc.curbside.asp.CurbStatus
import nyc.curbside.asp.NYC
import nyc.curbside.data.Vehicle
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.ui.humaniseDuration

private val PARKED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val MOVE_BY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE h:mm a")

@Composable
fun HomeScreen(
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.detectionOnMotionAlone) {
            MotionOnlyCard(onOpenSettings)
        }

        if (state.cars.isEmpty()) {
            NoCarCard(onOpenMap)
        } else {
            state.cars.forEach { row ->
                CarCard(
                    row = row,
                    vehicles = state.vehicles,
                    autoShareEnabled = state.autoShareEnabled,
                    onShare = { context.startActivity(viewModel.shareIntent(row.event)) },
                    onNavigate = { context.startActivity(navigateIntent(row.event)) },
                    onDroveAway = { viewModel.onDroveAway(row.event.id) },
                    onOpenMap = onOpenMap,
                    onVehicleChosen = { viewModel.onVehicleChosen(row.event.id, it) },
                )
            }
        }
    }
}

/** Says plainly that the most reliable signal is switched off, and offers to switch it on. */
@Composable
private fun MotionOnlyCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Detection is running on motion alone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Curbside is watching your phone's motion sensor for the start and end of a " +
                    "drive. That works, but it is slower than the alternative, it can mistake a " +
                    "walk for a drive, and it cannot tell one car from another.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Pick your car stereo and Curbside knows a drive ended the moment the stereo " +
                    "drops — the ignition going off, rather than a guess.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            TextButton(onClick = onOpenSettings) { Text("Choose car stereo") }
        }
    }
}

@Composable
private fun NoCarCard(onOpenMap: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No car parked", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Curbside drops a pin on its own when you finish a drive — when the head unit " +
                    "disconnects, when your stereo unpairs, or when your phone notices you have " +
                    "started walking.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenMap) { Text("Look at the rules map") }
        }
    }
}

@Composable
private fun CarCard(
    row: ParkedCarRow,
    vehicles: List<Vehicle>,
    autoShareEnabled: Boolean,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onDroveAway: () -> Unit,
    onOpenMap: () -> Unit,
    onVehicleChosen: (String) -> Unit,
) {
    val car = row.event
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The car's name leads, because with two of them "move it by Thursday" is useless
            // until you know which one is meant.
            Text(
                row.vehicleName ?: "Car",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline,
            )

            StatusHeadline(row)

            car.address?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            row.curbLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }

            Text(
                "Parked ${PARKED_AT.format(java.time.Instant.ofEpochMilli(car.parkedAt).atZone(NYC))}" +
                    accuracyNote(car),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (row.byPartner) {
                Text(
                    "Left here by someone else in your household.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (row.evaluation?.partiallyRestricted == true) {
                Text(
                    "Part of this block is no standing at any time. Curbside cannot tell which " +
                        "stretch — check the sign next to the car.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (row.needsVehicle && vehicles.isNotEmpty()) {
                WhichCarPrompt(vehicles, onVehicleChosen)
            }

            if (row.needsSideConfirmation) {
                HorizontalDivider()
                Text(
                    "The fix was too rough to tell which side of the street you are on, and the " +
                        "two sides are cleaned on different days. Tap the curb on the map to set it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onOpenMap) { Text("Pick the side on the map") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNavigate) { Text("Walk to car") }
                if (!row.byPartner) {
                    OutlinedButton(onClick = onShare) { Text("Share") }
                    OutlinedButton(onClick = onDroveAway) { Text("I moved it") }
                }
            }

            if (autoShareEnabled && !row.byPartner) {
                Text(
                    "Shared with your household automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * The fallback of the fallback: no stereo spoke and the drive did not start anywhere a known car
 * was left, so the app has to ask rather than name the wrong one.
 */
@Composable
private fun WhichCarPrompt(vehicles: List<Vehicle>, onChosen: (String) -> Unit) {
    HorizontalDivider()
    Text("Which car is this?", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Curbside could not tell — no stereo connected, and the drive did not start where either " +
            "car was left.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vehicles.forEach { vehicle ->
            OutlinedButton(onClick = { onChosen(vehicle.id) }) { Text(vehicle.name) }
        }
    }
}

/**
 * The single most important thing on the screen: how long until the car has to move, in the colour
 * the map uses for the same state.
 */
@Composable
private fun StatusHeadline(row: ParkedCarRow) {
    val status = row.status
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(14.dp)
                .background(Color(android.graphics.Color.parseColor(status.lightHex)), CircleShape),
        )
        Column {
            Text(
                text = countdownText(row),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            row.evaluation?.moveBy?.let {
                Text(
                    "Cleaning starts ${MOVE_BY.format(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun countdownText(row: ParkedCarRow): String {
    val remaining = row.timeUntilMove
    return when {
        row.status == CurbStatus.RESTRICTED_NOW -> "Move it now"
        row.status == CurbStatus.ALWAYS_RESTRICTED -> "No parking here at any time"
        row.status == CurbStatus.SUSPENDED_TODAY -> "Alternate side suspended today"
        row.status == CurbStatus.UNKNOWN -> "No sign data for this curb"
        remaining == null -> row.status.label
        else -> "Move in ${humaniseDuration(remaining)}"
    }
}

private fun accuracyNote(car: ParkingEventEntity): String = when (car.fixQuality) {
    "BREADCRUMB" -> " · approximate, last position before you lost signal"
    "MANUAL" -> " · pin you dropped"
    // Not "accurate to about 47m": that reads as a promise, and the number is a 68% confidence
    // radius the true error regularly exceeds. A fix this vague is a guess and should look like one.
    "COARSE" -> " · rough guess only, the fix was poor"
    else -> " · accurate to about ${car.accuracyMeters.toInt()}m"
}

private fun navigateIntent(car: ParkingEventEntity): Intent {
    val uri = android.net.Uri.parse("geo:${car.latitude},${car.longitude}?q=${car.latitude},${car.longitude}(Car)")
    return Intent(Intent.ACTION_VIEW, uri)
}
