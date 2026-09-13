package nyc.curbside.asp

import java.time.Duration
import java.time.ZonedDateTime

/**
 * The answer to "how long can I leave the car here?"
 *
 * [CurbEvaluation] answers the driver's question at the moment of parking — what colour is this
 * curb, when must I move. This answers the question asked while still driving, looking at a block
 * on the map: is that spot any use to me, and for how long.
 *
 * The two are deliberately different shapes. A curb whose window is open right now is
 * [CurbStatus.RESTRICTED_NOW] and useless to a parked car, but to someone circling the block it is
 * a spot that frees up in twenty minutes, which is worth knowing and worth saying.
 */
sealed interface ParkingAllowance {

    /** No usable sign data for this curb. Not the same as "no restrictions". */
    data object Unknown : ParkingAllowance

    /** No standing or parking at any hour. Waiting does not help. */
    data object Never : ParkingAllowance

    /**
     * A span in which the car may legally stand.
     *
     * @param from when the spot becomes available; null means right now.
     * @param until when it must be gone by; null means no restriction within the search horizon,
     *   which for rules that repeat weekly means no restriction at all.
     */
    data class Free(val from: ZonedDateTime?, val until: ZonedDateTime?) : ParkingAllowance {

        val isImmediate: Boolean get() = from == null

        /** How long the car may stay, measured from [from] or from [now] if it may park already. */
        fun duration(now: ZonedDateTime): Duration? =
            until?.let { Duration.between(from ?: now, it) }
    }
}

/**
 * Turns a curb's weekly rules into the next span of time a car may stand there.
 *
 * Lives alongside [SweepSchedule] and shares its window expansion, so the map's colour, the move-by
 * reminder and this can never disagree about when a window opens.
 */
object ParkingWindow {

    /**
     * @param now the moment to answer for — the map passes its scrubbed preview time here, so
     *   dragging the slider re-answers the question for Thursday morning rather than for today.
     */
    fun allowance(
        regulations: List<Regulation>,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
        horizonDays: Int = SweepSchedule.HORIZON_DAYS,
        alongMeters: Double? = null,
    ): ParkingAllowance {
        @Suppress("NAME_SHADOWING")
        val regulations = regulations.filter { it.governs(alongMeters) }
        if (regulations.isEmpty()) return ParkingAllowance.Unknown

        // Same guard as the evaluator: a sign nobody could parse is not a sign saying "park here".
        if (regulations.any { it.kind == RegulationKind.OTHER && it.days.isEmpty() }) {
            return ParkingAllowance.Unknown
        }

        // Advisory rules — meters, time limits — cap how long you may stay but never make the curb
        // illegal, and the app does not model their durations. They are listed on the detail sheet
        // as rules; they do not shorten the span computed here. An anytime sign that governs only
        // part of the block is set aside the same way, and reported by
        // [CurbEvaluation.partiallyRestricted] rather than folded into the span.
        val rules = SweepSchedule.classify(regulations)
        if (rules.condemned) return ParkingAllowance.Never
        if (rules.governing.isEmpty()) return ParkingAllowance.Free(from = null, until = null)

        val local = now.withZoneSameInstant(NYC)
        val spans = merge(
            SweepSchedule.expand(rules.governing, local.toLocalDate(), horizonDays, calendar),
        )

        val open = spans.firstOrNull { local >= it.start && local < it.end }
        val waitUntil = open?.end

        // The first restriction that begins after the car would have arrived. Merging above means
        // a span starting exactly when the current one ends has already been folded in, so this is
        // never the tail of the very block of time we are waiting out.
        val nextStart = spans.firstOrNull { it.start.isAfter(waitUntil ?: local) }?.start

        return ParkingAllowance.Free(from = waitUntil, until = nextStart)
    }

    /** Convenience for callers that already hold a whole curb. */
    fun allowance(
        segment: CurbSegment,
        now: ZonedDateTime,
        calendar: SuspensionCalendar = SuspensionCalendar.EMPTY,
    ): ParkingAllowance = allowance(segment.regulations, now, calendar)

    /**
     * Collapses overlapping and back-to-back windows into single spans.
     *
     * Two rules that run 8-9:30 and 9:30-11 on the same day leave no moment in between when a car
     * may stand there, so reporting "free at 9:30" would be a lie of exactly the kind this app
     * exists not to tell. Reported as one span to 11, it is the truth.
     */
    private fun merge(windows: List<RestrictionWindow>): List<Span> {
        val out = ArrayList<Span>(windows.size)
        for (window in windows) {
            val last = out.lastOrNull()
            if (last != null && !window.start.isAfter(last.end)) {
                if (window.end.isAfter(last.end)) out[out.lastIndex] = last.copy(end = window.end)
            } else {
                out += Span(window.start, window.end)
            }
        }
        return out
    }

    private data class Span(val start: ZonedDateTime, val end: ZonedDateTime)
}
