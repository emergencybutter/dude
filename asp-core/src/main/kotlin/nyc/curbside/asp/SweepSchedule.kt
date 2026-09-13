package nyc.curbside.asp

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * One occurrence of a regulation on a concrete date, resolved into wall-clock instants in NYC time.
 */
data class RestrictionWindow(
    val regulation: Regulation,
    val date: LocalDate,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    operator fun contains(moment: ZonedDateTime): Boolean = !moment.isBefore(start) && moment.isBefore(end)
}

/**
 * The answer to "what is going on at this curb, and when do I have to move?"
 *
 * [nextStart] is what drives the reminder notification; [current] is what drives the map colour
 * when a window is already open.
 */
data class CurbEvaluation(
    val status: CurbStatus,
    /** The window in force right now, if any. */
    val current: RestrictionWindow?,
    /** The next window that will open, if one is known within the search horizon. */
    val next: RestrictionWindow?,
    /** True when a street-cleaning window was cancelled today by a citywide suspension. */
    val suspendedToday: Boolean,
) {
    /** The moment the car has to be gone by. Null when nothing is scheduled within the horizon. */
    val moveBy: ZonedDateTime? get() = next?.start

    val governing: Regulation? get() = current?.regulation ?: next?.regulation
}

/**
 * Expands weekly [Regulation]s into dated windows and grades the result.
 *
 * All arithmetic goes through [java.time.ZonedDateTime] in [NYC], so the twice-yearly DST shifts
 * land on the correct wall-clock hour: an 8:00am sweeping is 8:00am local on both sides of the
 * change, even though the two dates are 23 and 25 hours apart.
 */
object SweepSchedule {

    /** How far ahead to look. Eight days guarantees at least one hit for any weekly rule. */
    const val HORIZON_DAYS: Int = 8

    /**
     * If today's cleaning is suspended but another window opens within this many hours, the map
     * shows the real upcoming window instead of the "suspended" badge. Hiding an imminent move
     * behind good news is the one failure mode worth engineering against.
     */
    private const val SUSPENSION_BADGE_MAX_HOURS: Long = 12

    fun evaluate(
        regulations: List<Regulation>,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
        horizonDays: Int = HORIZON_DAYS,
    ): CurbEvaluation {
        if (regulations.isEmpty()) {
            return CurbEvaluation(CurbStatus.UNKNOWN, null, null, suspendedToday = false)
        }

        // A sign we failed to parse is not a sign that says "park here".
        if (regulations.any { it.kind == RegulationKind.OTHER && it.days.isEmpty() }) {
            return CurbEvaluation(CurbStatus.UNKNOWN, null, null, suspendedToday = false)
        }

        val enforceable = regulations.filterNot { it.isAdvisory }
        if (enforceable.isEmpty()) {
            return CurbEvaluation(CurbStatus.CLEAR, null, null, suspendedToday = false)
        }

        if (enforceable.any(::isAroundTheClock)) {
            return CurbEvaluation(CurbStatus.ALWAYS_RESTRICTED, null, null, suspendedToday = false)
        }

        val local = now.withZoneSameInstant(NYC)
        val today = local.toLocalDate()
        val windows = expand(enforceable, today, horizonDays, calendar)

        val current = windows.firstOrNull { local in it }
        val next = windows.firstOrNull { it.start.isAfter(local) }

        val suspendedToday = calendar.isSuspended(today) &&
            enforceable.any { it.isSuspendable && it.appliesOn(today.dayOfWeek) }

        val status = when {
            current != null -> CurbStatus.RESTRICTED_NOW
            next == null -> if (suspendedToday) CurbStatus.SUSPENDED_TODAY else CurbStatus.CLEAR
            else -> {
                val minutes = Duration.between(local, next.start).toMinutes()
                val graded = CurbStatus.fromMinutesUntil(minutes)
                val farEnoughOut = minutes >= SUSPENSION_BADGE_MAX_HOURS * 60
                if (suspendedToday && farEnoughOut) CurbStatus.SUSPENDED_TODAY else graded
            }
        }

        return CurbEvaluation(status, current, next, suspendedToday)
    }

    /** Convenience for the map layer, which evaluates a whole viewport of segments at once. */
    fun evaluate(
        segment: CurbSegment,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
    ): CurbEvaluation = evaluate(segment.regulations, now, calendar)

    /** Shared with [ParkingWindow], which must call a curb hopeless on exactly the same grounds. */
    internal fun isAroundTheClock(regulation: Regulation): Boolean =
        regulation.isAllDay &&
            regulation.days.size == 7 &&
            regulation.kind in setOf(
                RegulationKind.NO_PARKING,
                RegulationKind.NO_STANDING,
                RegulationKind.NO_STOPPING,
            )

    /**
     * Produces every window from the start of [today] through [horizonDays] later, ordered by
     * start time. Suspended street-cleaning occurrences are dropped here so callers never see them.
     */
    internal fun expand(
        regulations: List<Regulation>,
        today: LocalDate,
        horizonDays: Int,
        calendar: SuspensionCalendar,
    ): List<RestrictionWindow> {
        val out = ArrayList<RestrictionWindow>()
        for (offset in 0..horizonDays) {
            val date = today.plusDays(offset.toLong())
            for (regulation in regulations) {
                if (!regulation.appliesOn(date.dayOfWeek)) continue
                if (regulation.isSuspendable && calendar.isSuspended(date)) continue

                val window = regulation.window
                val startTime = window?.start ?: LocalTime.MIDNIGHT
                val endTime = window?.end ?: SignParser.END_OF_DAY

                out += RestrictionWindow(
                    regulation = regulation,
                    date = date,
                    start = date.atTime(startTime).atZone(NYC),
                    end = date.atTime(endTime).atZone(NYC),
                )
            }
        }
        out.sortBy { it.start }
        return out
    }
}
