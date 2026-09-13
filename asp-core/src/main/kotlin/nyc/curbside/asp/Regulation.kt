package nyc.curbside.asp

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/** New York City observes a single timezone; every schedule calculation is anchored to it. */
val NYC: ZoneId = ZoneId.of("America/New_York")

/**
 * What a curb regulation actually forbids. The distinction matters for two reasons: alternate side
 * suspensions apply to [STREET_CLEANING] and nothing else, and only [STREET_CLEANING] is a
 * "come back in 90 minutes" rule — the others mean the spot is simply not available.
 */
enum class RegulationKind {
    /** "NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS" — alternate side street cleaning. */
    STREET_CLEANING,
    NO_PARKING,
    NO_STANDING,
    NO_STOPPING,

    /** Metered or time-limited parking. Legal to park, just not indefinitely. */
    TIME_LIMITED,
    OTHER,
}

/**
 * A clock-time span within a single day. Alternate side windows never wrap past midnight, so
 * [start] is always strictly before [end]; the parser rejects anything that would wrap.
 *
 * [end] is exclusive: a 9:00-10:30 window is over at 10:30:00.
 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    init {
        require(start < end) { "TimeWindow must not wrap midnight: $start-$end" }
    }

    val durationMinutes: Int
        get() = (end.toSecondOfDay() - start.toSecondOfDay()) / 60

    operator fun contains(time: LocalTime): Boolean = time >= start && time < end

    override fun toString(): String = "${clock(start)}-${clock(end)}"

    companion object {
        /**
         * A wall-clock time as a sign writes it: "8am", "9:30am".
         *
         * Public because the UI renders single moments ("free again at 9:30am") as well as windows,
         * and the two must not drift into different house styles on the same screen.
         */
        fun clock(t: LocalTime): String {
            val hour = when (val h = t.hour % 12) {
                0 -> 12
                else -> h
            }
            val suffix = if (t.hour < 12) "am" else "pm"
            return if (t.minute == 0) "$hour$suffix" else "$hour:%02d%s".format(t.minute, suffix)
        }
    }
}

/**
 * One parsed clause off a DOT sign: what is forbidden, on which weekdays, between which hours.
 *
 * A null [window] means the rule is in force all day ("NO STANDING ANYTIME").
 */
data class Regulation(
    val kind: RegulationKind,
    val days: Set<DayOfWeek>,
    val window: TimeWindow?,
    val raw: String,
    /** True when [days] was assumed rather than read off the sign. Lowers display confidence. */
    val daysInferred: Boolean = false,
) {
    val isAllDay: Boolean get() = window == null

    /** Alternate side suspensions (holidays, snow emergencies) lift this rule and nothing else. */
    val isSuspendable: Boolean get() = kind == RegulationKind.STREET_CLEANING

    /** A rule you can ignore when deciding whether the car is about to get a ticket. */
    val isAdvisory: Boolean get() = kind == RegulationKind.TIME_LIMITED || kind == RegulationKind.OTHER

    fun appliesOn(day: DayOfWeek): Boolean = day in days
}

/** Which side of the street a run of signs governs. Sweeping is per-side, so this is load-bearing. */
enum class StreetSide { NORTH, SOUTH, EAST, WEST, UNKNOWN;

    companion object {
        fun parse(raw: String?): StreetSide = when (raw?.trim()?.uppercase()) {
            "N", "NORTH" -> NORTH
            "S", "SOUTH" -> SOUTH
            "E", "EAST" -> EAST
            "W", "WEST" -> WEST
            else -> UNKNOWN
        }
    }
}

/**
 * A curb: one side of one block, with every regulation posted along it.
 *
 * [id] is stable across data refreshes so a saved parking spot keeps pointing at the same curb.
 */
data class CurbSegment(
    val id: String,
    val onStreet: String,
    val fromStreet: String,
    val toStreet: String,
    val side: StreetSide,
    val regulations: List<Regulation>,
)
