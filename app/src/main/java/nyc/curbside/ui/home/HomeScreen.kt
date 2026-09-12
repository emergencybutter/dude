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
import java.time.Duration
import java.time.format.DateTimeFormatter
import nyc.curbside.asp.CurbStatus
import nyc.curbside.asp.NYC
import nyc.curbside.data.db.ParkingEventEntity

private val PARKED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val MOVE_BY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE h:mm a")

@Composable
fun HomeScreen(onOpenMap: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val car = state.car
        if (car == null) {
            NoCarCard(onOpenMap)
        } else {
            CarCard(
                car = car,
                state = state,
                onShare = { viewModel.shareIntent()?.let(context::startActivity) },
                onNavigate = { context.startActivity(navigateIntent(car)) },
                onDroveAway = viewModel::onDroveAway,
                onOpenMap = onOpenMap,
            )
        }

        state.partnerCars.forEach { partnerCar ->
            PartnerCard(partnerCar) { context.startActivity(navigateIntent(partnerCar)) }
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
    car: ParkingEventEntity,
    state: HomeUiState,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onDroveAway: () -> Unit,
    onOpenMap: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusHeadline(state)

            car.address?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            state.curbLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }

            Text(
                "Parked ${PARKED_AT.format(java.time.Instant.ofEpochMilli(car.parkedAt).atZone(NYC))}" +
                    accuracyNote(car),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (state.needsSideConfirmation) {
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
                OutlinedButton(onClick = onShare) { Text("Share") }
                OutlinedButton(onClick = onDroveAway) { Text("I moved it") }
            }

            if (state.autoShareEnabled) {
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
 * The single most important thing on the screen: how long until the car has to move, in the colour
 * the map uses for the same state.
 */
@Composable
private fun StatusHeadline(state: HomeUiState) {
    val status = state.status
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(14.dp)
                .background(Color(android.graphics.Color.parseColor(status.lightHex)), CircleShape),
        )
        Column {
            Text(
                text = countdownText(state),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.evaluation?.moveBy?.let {
                Text(
                    "Cleaning starts ${MOVE_BY.format(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun countdownText(state: HomeUiState): String {
    val remaining = state.timeUntilMove
    return when {
        state.status == CurbStatus.RESTRICTED_NOW -> "Move it now"
        state.status == CurbStatus.ALWAYS_RESTRICTED -> "No parking here at any time"
        state.status == CurbStatus.SUSPENDED_TODAY -> "Alternate side suspended today"
        state.status == CurbStatus.UNKNOWN -> "No sign data for this curb"
        remaining == null -> state.status.label
        else -> "Move in ${humanise(remaining)}"
    }
}

private fun humanise(duration: Duration): String {
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun accuracyNote(car: ParkingEventEntity): String = when (car.fixQuality) {
    "BREADCRUMB" -> " · approximate, last position before you lost signal"
    "MANUAL" -> " · pin you dropped"
    else -> " · accurate to about ${car.accuracyMeters.toInt()}m"
}

@Composable
private fun PartnerCard(car: ParkingEventEntity, onNavigate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shared with you", style = MaterialTheme.typography.labelLarge)
            Text(car.address ?: "Location shared", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onNavigate) { Text("Walk there") }
        }
    }
}

private fun navigateIntent(car: ParkingEventEntity): Intent {
    val uri = android.net.Uri.parse("geo:${car.latitude},${car.longitude}?q=${car.latitude},${car.longitude}(Car)")
    return Intent(Intent.ACTION_VIEW, uri)
}
