package nyc.curbside.asp

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Turns the free-text `sign_description` column of the NYC DOT sign inventory into structured
 * [Regulation]s.
 *
 * The column is transcribed sign copy, not a schema, so it is inconsistent by nature. Real values
 * look like:
 *
 * ```
 * NO PARKING (SANITATION BROOM SYMBOL) 11:30AM-1PM TUES & FRI
 * NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS
 * NO STANDING 7AM-10AM MON THRU FRI
 * NO STANDING ANYTIME
 * 2 HOUR PARKING 9AM-7PM EXCEPT SUNDAY
 * ```
 *
 * The parser is deliberately conservative: anything it cannot read confidently comes back as
 * [RegulationKind.OTHER] with empty days rather than as a guess, because a wrong "you're fine here"
 * costs the user $65 and a wrong "move now" costs them their parking spot. The offline pipeline
 * reports parse coverage so unrecognised phrasings can be added deliberately.
 */
object SignParser {

    /** Multi-clause signs separate rules with a slash or a newline. */
    private val CLAUSE_SPLIT = Regex("""\s*(?:/|\r?\n)\s*""")

    private val BROOM = Regex("""SANITATION\s+BROOM|BROOM\s+SYMBOL|STREET\s+CLEANING""")

    /** One endpoint of a time range: `8`, `9:30`, `11:30AM`, `MIDNIGHT`, `NOON`. */
    private const val ENDPOINT = """(?:\d{1,2}(?::\d{2})?\s*(?:AM|PM)?|MIDNIGHT|NOON)"""

    private val RANGE = Regex("""\b($ENDPOINT)\s*(?:-|–|—|\bTO\b|\bTILL?\b)\s*($ENDPOINT)\b""")

    private val ENDPOINT_PARTS = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?$""")

    private val DAY_TOKENS: List<Pair<Regex, DayOfWeek>> = listOf(
        Regex("""\bMONDAYS?\b|\bMONS?\b""") to DayOfWeek.MONDAY,
        Regex("""\bTUESDAYS?\b|\bTUES\b|\bTUE\b|\bTU\b""") to DayOfWeek.TUESDAY,
        Regex("""\bWEDNESDAYS?\b|\bWEDS\b|\bWED\b""") to DayOfWeek.WEDNESDAY,
        Regex("""\bTHURSDAYS?\b|\bTHURS\b|\bTHUR\b|\bTHU\b|\bTH\b""") to DayOfWeek.THURSDAY,
        Regex("""\bFRIDAYS?\b|\bFRI\b""") to DayOfWeek.FRIDAY,
        Regex("""\bSATURDAYS?\b|\bSATS?\b""") to DayOfWeek.SATURDAY,
        Regex("""\bSUNDAYS?\b|\bSUNS?\b""") to DayOfWeek.SUNDAY,
    )

    private const val ANY_DAY =
        """MON(?:DAYS?|S)?|TUES?(?:DAYS?)?|TU|WED(?:NESDAYS?|S)?|THU(?:RS?|RSDAYS?)?|TH|FRI(?:DAYS?)?|SAT(?:URDAYS?|S)?|SUN(?:DAYS?|S)?"""

    private val DAY_RANGE = Regex("""\b($ANY_DAY)\s*(?:-|–|—|THRU|THROUGH|TO)\s*($ANY_DAY)\b""")

    private val EXCEPT = Regex("""\bEXCEPT\b(.*)$""")

    /** Exclusive end-of-day marker, so a window that runs "until midnight" covers 23:59. */
    internal val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59)

    private val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    private val WEEKDAYS: Set<DayOfWeek> = ALL_DAYS - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY

    /**
     * Parses one `sign_description` value. Returns one [Regulation] per clause; an unreadable
     * description yields a single [RegulationKind.OTHER] regulation preserving the raw text.
     */
    fun parse(description: String): List<Regulation> {
        val normalized = normalize(description)
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(CLAUSE_SPLIT)
            .filter { it.isNotBlank() }
            .map { parseClause(it, description) }
    }

    private fun normalize(raw: String): String =
        raw.uppercase()
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("""[ \t]+"""), " ")
            .trim()

    private fun parseClause(clause: String, rawSign: String): Regulation {
        val kind = classify(clause)
        val window = parseWindow(clause)
        val anytime = clause.contains("ANYTIME") || clause.contains("ALL TIMES")

        // "ANYTIME" beats everything: the rule is in force 24/7 regardless of any stray digits.
        if (anytime) {
            return Regulation(kind, ALL_DAYS, window = null, raw = rawSign, daysInferred = true)
        }

        val explicit = parseDays(clause)
        val days = explicit ?: if (window != null || kind != RegulationKind.OTHER) ALL_DAYS else emptySet()

        // A timed rule we could not read at all is worse than useless — surface it as OTHER so the
        // UI shows "unknown rules" instead of inventing a schedule.
        if (days.isEmpty()) {
            return Regulation(RegulationKind.OTHER, emptySet(), null, rawSign, daysInferred = false)
        }

        return Regulation(
            kind = kind,
            days = days,
            window = window,
            raw = rawSign,
            daysInferred = explicit == null,
        )
    }

    private fun classify(clause: String): RegulationKind = when {
        BROOM.containsMatchIn(clause) -> RegulationKind.STREET_CLEANING
        clause.contains("NO STOPPING") -> RegulationKind.NO_STOPPING
        clause.contains("NO STANDING") -> RegulationKind.NO_STANDING
        clause.contains("NO PARKING") -> RegulationKind.NO_PARKING
        Regex("""\b\d+\s*(?:HOUR|HR|MINUTE|MIN)\b""").containsMatchIn(clause) -> RegulationKind.TIME_LIMITED
        clause.contains("METERED") || clause.contains("MUNI-METER") -> RegulationKind.TIME_LIMITED
        else -> RegulationKind.OTHER
    }

    /** Reads the first `H:MM-H:MM` style range in the clause, resolving implied am/pm. */
    internal fun parseWindow(clause: String): TimeWindow? {
        val match = RANGE.find(clause) ?: return null
        val (leftRaw, rightRaw) = match.destructured

        // "7AM-MIDNIGHT" means until the day runs out, not back to 00:00.
        val right = if (rightRaw.trim() == "MIDNIGHT") {
            END_OF_DAY
        } else {
            parseEndpoint(rightRaw, assumed = null) ?: return null
        }
        // "8-9:30AM": the left endpoint borrows the right endpoint's meridiem.
        val rightMeridiemPm = right.hour >= 12
        var left = parseEndpoint(leftRaw, assumed = rightMeridiemPm) ?: return null

        // "11-12:30PM" borrows PM and becomes 23:00-12:30, which wraps. The sign meant 11AM.
        if (left >= right) {
            left = parseEndpoint(leftRaw, assumed = false) ?: return null
        }
        if (left >= right) return null

        return TimeWindow(left, right)
    }

    /**
     * @param assumed when the endpoint omits AM/PM, true forces PM and false forces AM. Null means
     *   the endpoint is expected to carry its own meridiem (or be MIDNIGHT/NOON) and AM is the
     *   fallback, matching how the DOT transcribes morning sweeping windows.
     */
    private fun parseEndpoint(raw: String, assumed: Boolean?): LocalTime? {
        val token = raw.trim()
        when (token) {
            "MIDNIGHT" -> return LocalTime.MIDNIGHT
            "NOON" -> return LocalTime.NOON
        }
        val m = ENDPOINT_PARTS.matchEntire(token) ?: return null
        val hour12 = m.groupValues[1].toIntOrNull() ?: return null
        if (hour12 !in 1..12) return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        if (minute !in 0..59) return null

        val pm = when (m.groupValues[3]) {
            "AM" -> false
            "PM" -> true
            else -> assumed ?: false
        }
        val hour24 = when {
            hour12 == 12 && !pm -> 0
            hour12 == 12 -> 12
            pm -> hour12 + 12
            else -> hour12
        }
        return LocalTime.of(hour24, minute)
    }

    /** Returns null when the clause names no weekdays at all, versus an empty set for "no days". */
    internal fun parseDays(clause: String): Set<DayOfWeek>? {
        // "INCLUDING SUNDAY" is an emphasis, not a restriction: the rule runs the whole week.
        if (clause.contains("INCLUDING")) return ALL_DAYS

        // "EXCEPT SUNDAY" inverts: everything the clause did not exclude.
        EXCEPT.find(clause)?.let { except ->
            val excluded = scanDays(except.groupValues[1])
            if (excluded.isNotEmpty()) return ALL_DAYS - excluded
        }

        val ranged = mutableSetOf<DayOfWeek>()
        var remainder = clause
        DAY_RANGE.findAll(clause).forEach { m ->
            val from = singleDay(m.groupValues[1])
            val to = singleDay(m.groupValues[2])
            if (from != null && to != null) {
                ranged += expandRange(from, to)
                remainder = remainder.replace(m.value, " ")
            }
        }

        val singles = scanDays(remainder)
        val all = ranged + singles

        if (all.isNotEmpty()) return all
        if (clause.contains("SCHOOL DAYS")) return WEEKDAYS
        return null
    }

    private fun scanDays(text: String): Set<DayOfWeek> =
        DAY_TOKENS.filter { (regex, _) -> regex.containsMatchIn(text) }
            .map { (_, day) -> day }
            .toSet()

    private fun singleDay(token: String): DayOfWeek? =
        DAY_TOKENS.firstOrNull { (regex, _) -> regex.matches(token) || regex.containsMatchIn(token) }?.second

    /** Inclusive weekday range that wraps the week, so SAT-MON is Sat, Sun, Mon. */
    private fun expandRange(from: DayOfWeek, to: DayOfWeek): Set<DayOfWeek> {
        val out = linkedSetOf<DayOfWeek>()
        var cursor = from
        while (true) {
            out += cursor
            if (cursor == to) break
            cursor = cursor.plus(1)
            if (out.size > 7) break
        }
        return out
    }
}
