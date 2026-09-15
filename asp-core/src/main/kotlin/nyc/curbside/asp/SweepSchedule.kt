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
    /**
     * True when some stretch of this curb is restricted around the clock but the rest of it is
     * not — a bus stop, a hydrant, a driveway. The status describes the rest of the block; this
     * says the block is not uniform and the sign on the spot is the authority.
     */
    val partiallyRestricted: Boolean = false,
) {
    /** The moment the car has to be gone by. Null when nothing is scheduled within the horizon. */
    val moveBy: ZonedDateTime? get() = next?.start

    val governing: Regulation? get() = current?.regulation ?: next?.regulation
}

/** A day this curb's cleaning is called off, and what for. */
data class SuspendedDay(val date: LocalDate, val reason: String?)

/**
 * One run of a curb that has a single answer along its whole length.
 *
 * [geometry] is the slice of the curb's polyline this run covers, ready to draw on its own.
 */
data class CurbStretch(
    val geometry: List<LatLng>,
    val fromMeters: Double,
    val toMeters: Double,
    val evaluation: CurbEvaluation,
)

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

    /**
     * @param alongMeters where on the curb the question is being asked, in metres along its
     *   polyline. Rules that govern a different stretch are ignored. Null asks about the curb as a
     *   whole, which applies every rule on it — the honest answer when the position is unknown.
     */
    fun evaluate(
        regulations: List<Regulation>,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
        horizonDays: Int = HORIZON_DAYS,
        alongMeters: Double? = null,
    ): CurbEvaluation {
        @Suppress("NAME_SHADOWING")
        val regulations = regulations.filter { it.governs(alongMeters) }
        if (regulations.isEmpty()) {
            return CurbEvaluation(CurbStatus.UNKNOWN, null, null, suspendedToday = false)
        }

        // A sign we failed to parse is not a sign that says "park here".
        if (regulations.any { it.kind == RegulationKind.OTHER && it.days.isEmpty() }) {
            return CurbEvaluation(CurbStatus.UNKNOWN, null, null, suspendedToday = false)
        }

        val rules = classify(regulations)
        if (rules.condemned) {
            return CurbEvaluation(CurbStatus.ALWAYS_RESTRICTED, null, null, suspendedToday = false)
        }

        val enforceable = rules.governing
        if (enforceable.isEmpty()) {
            return CurbEvaluation(
                CurbStatus.CLEAR,
                null,
                null,
                suspendedToday = false,
                partiallyRestricted = rules.partiallyRestricted,
            )
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

        return CurbEvaluation(status, current, next, suspendedToday, rules.partiallyRestricted)
    }

    /**
     * Decides what an "anytime" sign on a curb actually condemns.
     *
     * The city's sign data is per sign, but a curb here is a whole block-side, so every sign posted
     * anywhere along the block arrives in one undifferentiated list. A "NO STANDING ANYTIME" sign
     * is nearly always a bus stop, a hydrant or a driveway governing the few metres it stands on —
     * and taken at face value it condemned the whole block, on half the curbs in the dataset.
     *
     * What settles it is the company the sign keeps. A block posted with alternate side cleaning,
     * or a meter, is a block where parking is expected somewhere: nobody sweeps a curb, or bills
     * for it, that may never be stood on. So an anytime rule alongside timed ones governs part of
     * the block; an anytime rule alone, with nothing to contradict it, governs all of it.
     *
     * Note what this deliberately does not do: it never discards the anytime rule as noise. The
     * curb is reported as partly restricted, and the sheet says so, because the alternative is
     * telling a driver a bus stop is a parking spot.
     */
    internal fun classify(regulations: List<Regulation>): CurbRules {
        val enforceable = regulations.filterNot { it.isAdvisory }
        val governing = enforceable.filterNot(::isAroundTheClock)
        if (governing.size == enforceable.size) {
            return CurbRules(governing, condemned = false, partiallyRestricted = false)
        }

        // Any rule that names hours is a rule that expects a car to be there at other hours.
        val contradicted = regulations.any { it.window != null }
        return if (contradicted) {
            CurbRules(governing, condemned = false, partiallyRestricted = true)
        } else {
            CurbRules(emptyList(), condemned = true, partiallyRestricted = false)
        }
    }

    /** The rules that decide a curb's status, and what the anytime signs on it amount to. */
    internal data class CurbRules(
        /** Enforceable rules with a scope narrower than the whole week; these get expanded. */
        val governing: List<Regulation>,
        /** The whole curb is restricted around the clock. */
        val condemned: Boolean,
        /** Some stretch of it is, and the rest follows [governing]. */
        val partiallyRestricted: Boolean,
    )

    /** Convenience for the map layer, which evaluates a whole viewport of segments at once. */
    fun evaluate(
        segment: CurbSegment,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
    ): CurbEvaluation = evaluate(segment.regulations, now, calendar)

    /**
     * The curb broken into runs that each have one answer, for drawing.
     *
     * A block is no longer one colour: a hydrant zone at its corner is restricted while the rest
     * follows the cleaning schedule, and painting the whole line red was the bug that started
     * this. The boundaries are the ends of every rule's extent; each run between them is evaluated
     * on the rules that reach it, and neighbouring runs that come out the same are joined up again
     * so an ordinary block is still one line.
     */
    fun stretches(
        curb: LocatedCurb,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
    ): List<CurbStretch> {
        val length = Geo.lengthMeters(curb.geometry)
        val regulations = curb.segment.regulations
        val cuts = regulations.mapNotNull { it.extent }
            .flatMap { listOf(it.startMeters, it.endMeters) }
            .filter { it > MIN_STRETCH_METERS && it < length - MIN_STRETCH_METERS }
            .distinct()
            .sorted()

        if (cuts.isEmpty() || length <= 0.0) {
            return listOf(CurbStretch(curb.geometry, 0.0, length, evaluate(regulations, now, calendar)))
        }

        val edges = (listOf(0.0) + cuts + listOf(length))
        val runs = ArrayList<CurbStretch>(edges.size - 1)
        for (i in 0 until edges.size - 1) {
            val from = edges[i]
            val to = edges[i + 1]
            // Asked at the midpoint: a boundary belongs to neither side, and a rule that ends here
            // must not bleed into the run beyond it.
            val evaluation = evaluate(regulations, now, calendar, HORIZON_DAYS, (from + to) / 2)

            val previous = runs.lastOrNull()
            if (previous != null && previous.evaluation.status == evaluation.status) {
                runs[runs.lastIndex] = previous.copy(toMeters = to)
            } else {
                runs += CurbStretch(emptyList(), from, to, evaluation)
            }
        }
        return runs.map { it.copy(geometry = Geo.slice(curb.geometry, it.fromMeters, it.toMeters)) }
    }

    /** Shorter than this and a run is survey noise, not a stretch of kerb worth drawing. */
    private const val MIN_STRETCH_METERS = 1.0

    /**
     * The days ahead when this curb's cleaning is called off.
     *
     * Only the days that would otherwise have been cleaning days here: a curb swept on Mondays and
     * Thursdays does not care that alternate side is suspended on a Tuesday, and listing every
     * holiday in the city would bury the one that matters. Rules that suspensions do not touch — a
     * hydrant, a bus stop — are ignored for the same reason.
     *
     * Bounded by what the calendar actually covers, so this never implies a promise about dates the
     * city has not published yet.
     */
    fun suspensionsAhead(
        regulations: List<Regulation>,
        now: ZonedDateTime,
        calendar: SuspensionCalendar,
        days: Int = SUSPENSION_LOOKAHEAD_DAYS,
    ): List<SuspendedDay> {
        val suspendable = regulations.filter { it.isSuspendable }
        if (suspendable.isEmpty()) return emptyList()

        val today = now.withZoneSameInstant(NYC).toLocalDate()
        return (0L..days.toLong())
            .map { today.plusDays(it) }
            .filter { date -> calendar.isSuspended(date) }
            .filter { date -> suspendable.any { it.appliesOn(date.dayOfWeek) } }
            .map { date -> SuspendedDay(date, calendar.reasonFor(date)) }
    }

    /**
     * How far ahead suspensions are listed.
     *
     * Longer than [HORIZON_DAYS], which exists to find the next restriction and only needs a week
     * to guarantee one. This answers "what is coming up", and the city publishes about six weeks
     * ahead, so there is no reason to show less than it knows.
     */
    const val SUSPENSION_LOOKAHEAD_DAYS: Int = 45

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
