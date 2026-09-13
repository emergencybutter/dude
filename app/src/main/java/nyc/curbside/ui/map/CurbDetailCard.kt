package nyc.curbside.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import nyc.curbside.asp.CurbDetail
import nyc.curbside.asp.ParkingAllowance
import nyc.curbside.asp.Regulation
import nyc.curbside.asp.RegulationKind
import nyc.curbside.asp.StreetSide
import nyc.curbside.asp.TimeWindow
import nyc.curbside.ui.humaniseDuration

/**
 * What one tapped curb says: where it is, how long you may leave a car on it, and the rules behind
 * that answer.
 *
 * The headline is the span, not the status. A status colour tells you how worried to be; someone
 * who has stopped to tap a specific block has already got past that and wants a number of hours.
 * The rules are listed underneath so the answer can be checked rather than trusted — the whole
 * dataset is transcribed sign copy and a curb whose rules look wrong is worth knowing about.
 */
@Composable
fun CurbDetailCard(detail: CurbDetail, onDismiss: () -> Unit) {
    val segment = detail.curb.curb.segment
    val status = detail.curb.evaluation.status

    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(
            Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(street(segment.onStreet), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${side(segment.side)} · ${street(segment.fromStreet)} to ${street(segment.toStreet)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(Color(android.graphics.Color.parseColor(status.lightHex)), CircleShape),
                )
                Text(status.label, style = MaterialTheme.typography.labelLarge)
            }

            Text(
                headline(detail),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            subhead(detail)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }

            if (detail.curb.evaluation.partiallyRestricted) {
                Text(
                    "Somewhere on this block a sign says no standing at any time — a bus stop, a " +
                        "hydrant or a driveway. Curbside cannot tell which stretch, so read the " +
                        "sign where you stop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            // With the tapped point in hand the rules divide: the ones governing that stretch of
            // kerb, and the ones posted further down the block. Listing them together was how a
            // bus stop came to look like the whole street's problem.
            val here = segment.regulations.filter { it.governs(detail.alongMeters) }
            val elsewhere = segment.regulations.size - here.size

            Text(
                when {
                    here.isEmpty() -> "No signs cover this spot"
                    detail.alongMeters != null -> "Signs covering this spot"
                    else -> "Signs on this curb"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            here.forEach { regulation ->
                Text(
                    "·  ${rule(regulation)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (elsewhere > 0) {
                Text(
                    if (elsewhere == 1) {
                        "One more sign applies to a different stretch of this block."
                    } else {
                        "$elsewhere more signs apply to other stretches of this block."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * The answer, in as few words as it can honestly be put.
 *
 * "Until further notice" is never said: the horizon is eight days and a rule could exist beyond it,
 * so the unrestricted case says what was actually checked.
 */
private fun headline(detail: CurbDetail): String = when (val allowance = detail.allowance) {
    ParkingAllowance.Unknown -> "No sign data here"
    ParkingAllowance.Never -> "No parking at any time"
    is ParkingAllowance.Free -> {
        val from = allowance.from
        val stay = allowance.duration(detail.at)
        when {
            from != null -> "Free in ${humaniseDuration(Duration.between(detail.at, from))}"
            stay == null -> "Nothing due for over a week"
            else -> "OK to park for ${humaniseDuration(stay)}"
        }
    }
}

private fun subhead(detail: CurbDetail): String? {
    val allowance = detail.allowance as? ParkingAllowance.Free ?: return when (detail.allowance) {
        ParkingAllowance.Unknown -> "The city's sign data does not cover this curb. It is not a promise that parking is allowed."
        else -> "No amount of waiting makes this curb legal."
    }

    val until = allowance.until
    val from = allowance.from

    return when {
        from != null && until != null ->
            "Restricted until ${moment(from, detail.at)}, then OK for " +
                "${humaniseDuration(Duration.between(from, until))} — until ${moment(until, detail.at)}"
        from != null -> "Restricted until ${moment(from, detail.at)}, then nothing due for over a week"
        until != null -> "${reason(detail)} at ${moment(until, detail.at)}"
        else -> "Nothing scheduled in the next eight days"
    }
}

/** What it is that happens at the end of the free span — a broom is worth naming as a broom. */
private fun reason(detail: CurbDetail): String = when (detail.curb.evaluation.next?.regulation?.kind) {
    RegulationKind.STREET_CLEANING -> "Street cleaning starts"
    RegulationKind.NO_PARKING -> "No parking starts"
    RegulationKind.NO_STANDING -> "No standing starts"
    RegulationKind.NO_STOPPING -> "No stopping starts"
    else -> "Restricted from"
}

private fun rule(regulation: Regulation): String {
    val what = when (regulation.kind) {
        RegulationKind.STREET_CLEANING -> "Street cleaning"
        RegulationKind.NO_PARKING -> "No parking"
        RegulationKind.NO_STANDING -> "No standing"
        RegulationKind.NO_STOPPING -> "No stopping"
        RegulationKind.TIME_LIMITED -> "Time limited"
        RegulationKind.OTHER -> "Other restriction"
    }
    val when_ = regulation.window?.toString() ?: "all day"
    val days = days(regulation.days)
    // A curb whose days were guessed rather than read says so: the app's rule is that it never
    // guesses in the driver's favour without admitting it.
    val hedge = if (regulation.daysInferred) " (days assumed)" else ""
    // Roughly how much kerb it covers. The exact metre marks mean nothing without knowing which
    // corner they are measured from, but a length tells you whether it is a block or a doorway.
    val reach = regulation.extent?.let { " · about ${it.lengthMeters.toInt()}m of kerb" } ?: ""
    return "$what · $when_ · $days$hedge$reach"
}

private fun days(days: Set<DayOfWeek>): String {
    if (days.isEmpty()) return "days unknown"
    if (days.size == 7) return "every day"

    val runs = mutableListOf<MutableList<DayOfWeek>>()
    for (day in days.sortedBy { it.value }) {
        val run = runs.lastOrNull()
        if (run != null && day.value == run.last().value + 1) run += day else runs += mutableListOf(day)
    }
    return runs.joinToString(", ") { run ->
        // Two consecutive days read better spelled out than hyphenated: "Mon, Tue" not "Mon-Tue".
        if (run.size > 2) "${abbreviate(run.first())}-${abbreviate(run.last())}" else run.joinToString(", ", transform = ::abbreviate)
    }
}

private fun abbreviate(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.US)

private fun moment(at: ZonedDateTime, now: ZonedDateTime): String {
    val clock = TimeWindow.clock(at.toLocalTime())
    return when (at.toLocalDate()) {
        now.toLocalDate() -> clock
        now.toLocalDate().plusDays(1) -> "tomorrow $clock"
        else -> "${abbreviate(at.dayOfWeek)} $clock"
    }
}

private fun side(side: StreetSide): String = when (side) {
    StreetSide.UNKNOWN -> "Side not recorded"
    else -> "${side.name.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }} side"
}

/** The city writes street names in block capitals; the app does not shout. */
private fun street(raw: String): String = raw
    .lowercase(Locale.US)
    .split(" ")
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase(Locale.US) } }
