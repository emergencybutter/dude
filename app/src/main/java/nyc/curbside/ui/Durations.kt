package nyc.curbside.ui

import java.time.Duration

/**
 * A span of time as the app says it everywhere: "2d 3h", "3h 20m", "45m".
 *
 * Shared between the home screen's countdown and the map's curb sheet so the same span never reads
 * two different ways in one session.
 */
fun humaniseDuration(duration: Duration): String {
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
