package nyc.curbside.asp

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every input string here is shaped like a real `sign_description` value from the NYC DOT sign
 * inventory. When the pipeline reports a new unparsed phrasing, add it here first.
 */
class SignParserTest {

    private fun one(text: String): Regulation {
        val parsed = SignParser.parse(text)
        assertEquals(1, parsed.size, "expected a single clause from: $text")
        return parsed.single()
    }

    @Test
    fun `reads a two-day sweeping sign`() {
        val r = one("NO PARKING (SANITATION BROOM SYMBOL) 11:30AM-1PM TUES & FRI")

        assertEquals(RegulationKind.STREET_CLEANING, r.kind)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), r.days)
        assertEquals(TimeWindow(LocalTime.of(11, 30), LocalTime.of(13, 0)), r.window)
        assertTrue(r.isSuspendable)
    }

    @Test
    fun `infers the opening meridiem from the closing one`() {
        val r = one("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS")

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), r.days)
        assertEquals(TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 30)), r.window)
    }

    @Test
    fun `does not let an inferred PM wrap the window past midnight`() {
        // Borrowing PM from "12:30PM" would give 23:00-12:30. The sign means 11 in the morning.
        val r = one("NO PARKING (SANITATION BROOM SYMBOL) 11-12:30PM WED")

        assertEquals(TimeWindow(LocalTime.of(11, 0), LocalTime.of(12, 30)), r.window)
    }

    @Test
    fun `expands a THRU day range`() {
        val r = one("NO STANDING 7AM-10AM MON THRU FRI")

        assertEquals(RegulationKind.NO_STANDING, r.kind)
        assertEquals(
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            ),
            r.days,
        )
        assertEquals(TimeWindow(LocalTime.of(7, 0), LocalTime.of(10, 0)), r.window)
    }

    @Test
    fun `expands a hyphenated day range`() {
        val r = one("NO STANDING 4PM-7PM MON-FRI")

        assertEquals(5, r.days.size)
        assertTrue(DayOfWeek.WEDNESDAY in r.days)
    }

    @Test
    fun `anytime means every day with no window`() {
        val r = one("NO STANDING ANYTIME")

        assertEquals(RegulationKind.NO_STANDING, r.kind)
        assertEquals(7, r.days.size)
        assertNull(r.window)
        assertTrue(r.isAllDay)
    }

    @Test
    fun `EXCEPT inverts the day set`() {
        val r = one("2 HOUR PARKING 9AM-7PM EXCEPT SUNDAY")

        assertEquals(RegulationKind.TIME_LIMITED, r.kind)
        assertEquals(6, r.days.size)
        assertTrue(DayOfWeek.SUNDAY !in r.days)
        assertTrue(r.isAdvisory)
    }

    @Test
    fun `midnight is start of day when opening and end of day when closing`() {
        val opening = one("NO PARKING MIDNIGHT-4AM")
        assertEquals(LocalTime.MIDNIGHT, opening.window?.start)
        assertEquals(LocalTime.of(4, 0), opening.window?.end)

        val closing = one("NO STOPPING 7AM-MIDNIGHT")
        assertEquals(LocalTime.of(7, 0), closing.window?.start)
        assertEquals(SignParser.END_OF_DAY, closing.window?.end)
    }

    @Test
    fun `noon parses as twelve hundred`() {
        val r = one("NO PARKING NOON-2PM SAT")

        assertEquals(LocalTime.NOON, r.window?.start)
        assertEquals(LocalTime.of(14, 0), r.window?.end)
        assertEquals(setOf(DayOfWeek.SATURDAY), r.days)
    }

    @Test
    fun `a timed sign with no weekday is assumed to run every day`() {
        val r = one("NO PARKING 8AM-6PM")

        assertEquals(7, r.days.size)
        assertTrue(r.daysInferred)
    }

    @Test
    fun `an unreadable sign is reported as unknown rather than guessed`() {
        val r = one("AUTHORIZED VEHICLES ONLY")

        assertEquals(RegulationKind.OTHER, r.kind)
        assertTrue(r.days.isEmpty())
    }

    @Test
    fun `splits a multi-clause sign`() {
        val parsed = SignParser.parse(
            "NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON / NO STANDING 4PM-7PM FRI",
        )

        assertEquals(2, parsed.size)
        assertEquals(RegulationKind.STREET_CLEANING, parsed[0].kind)
        assertEquals(setOf(DayOfWeek.MONDAY), parsed[0].days)
        assertEquals(RegulationKind.NO_STANDING, parsed[1].kind)
        assertEquals(setOf(DayOfWeek.FRIDAY), parsed[1].days)
    }

    @Test
    fun `the hour count in a time-limit sign is not mistaken for a window`() {
        val r = one("1 HOUR METERED PARKING 9AM-10PM INCLUDING SUNDAY")

        assertEquals(TimeWindow(LocalTime.of(9, 0), LocalTime.of(22, 0)), r.window)
    }

    @Test
    fun `blank input yields nothing`() {
        assertTrue(SignParser.parse("   ").isEmpty())
    }
}
