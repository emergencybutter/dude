package nyc.curbside.asp

import java.time.Instant
import java.time.LocalDate

/**
 * The days on which the city has lifted alternate side parking.
 *
 * Sourced from the NYC 311 public calendar API, which publishes roughly a quarter ahead. Anything
 * past [coverageEnd] is genuinely unknown rather than "not suspended", and [knows] exists so the UI
 * can say so instead of quietly promising a sweeping that may never happen.
 */
data class SuspensionCalendar(
    val suspendedDates: Set<LocalDate>,
    val coverageStart: LocalDate?,
    val coverageEnd: LocalDate?,
    val fetchedAt: Instant?,
    /** Why each date is suspended — "Yom Kippur" — where the city said. */
    val reasons: Map<LocalDate, String> = emptyMap(),
) {
    fun reasonFor(date: LocalDate): String? = reasons[date]?.takeIf { it.isNotBlank() }

    fun knows(date: LocalDate): Boolean {
        val start = coverageStart ?: return false
        val end = coverageEnd ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }

    /** False for any date outside the known window, so callers never treat a gap as a suspension. */
    fun isSuspended(date: LocalDate): Boolean = knows(date) && date in suspendedDates

    companion object {
        /** Used before the first successful fetch; makes every date "not known to be suspended". */
        val EMPTY = SuspensionCalendar(emptySet(), null, null, null)
    }
}
