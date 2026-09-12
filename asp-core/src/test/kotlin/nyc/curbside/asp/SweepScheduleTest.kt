package nyc.curbside.asp

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SweepScheduleTest {

    /** "NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS" — the archetypal Brooklyn sign. */
    private val monThuSweeping = SignParser
        .parse("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON THURS")
        .single()

    private fun nyc(date: String, time: String) =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), NYC)

    private fun suspend(vararg dates: String) = SuspensionCalendar(
        suspendedDates = dates.map(LocalDate::parse).toSet(),
        coverageStart = LocalDate.parse("2026-01-01"),
        coverageEnd = LocalDate.parse("2026-12-31"),
        fetchedAt = null,
    )

    private val noSuspensions = suspend()

    @Test
    fun `a curb inside its window reads as restricted now`() {
        // Monday 2026-09-14, 08:45 — squarely inside the 8:00-9:30 window.
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-14", "08:45"), noSuspensions)

        assertEquals(CurbStatus.RESTRICTED_NOW, result.status)
        assertNotNull(result.current)
        assertEquals(LocalDate.parse("2026-09-14"), result.current?.date)
    }

    @Test
    fun `the window is closed at its end time`() {
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-14", "09:30"), noSuspensions)

        assertNull(result.current)
        // Next window is Thursday morning, seventy hours out.
        assertEquals(nyc("2026-09-17", "08:00"), result.moveBy)
        assertEquals(CurbStatus.CLEAR, result.status)
    }

    @Test
    fun `the day before a sweeping falls in the two-day bucket`() {
        // Saturday noon; next sweeping is Monday 08:00, forty-four hours out.
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-12", "12:00"), noSuspensions)

        assertEquals(CurbStatus.MOVE_IN_TWO_DAYS, result.status)
        assertEquals(nyc("2026-09-14", "08:00"), result.moveBy)
    }

    @Test
    fun `under an hour out is the move-now warning`() {
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-14", "07:15"), noSuspensions)

        assertEquals(CurbStatus.MOVE_WITHIN_HOUR, result.status)
        assertEquals(nyc("2026-09-14", "08:00"), result.moveBy)
    }

    @Test
    fun `overnight before a sweeping is the move-today warning`() {
        // Sunday 23:00, sweeping at 08:00 Monday: nine hours out.
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-13", "23:00"), noSuspensions)

        assertEquals(CurbStatus.MOVE_TODAY, result.status)
        assertEquals(nyc("2026-09-14", "08:00"), result.moveBy)
    }

    @Test
    fun `a spot with nothing due for days reads as clear`() {
        // Thursday 10:00, just after the window: next is Monday, four days out.
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-17", "10:00"), noSuspensions)

        assertEquals(CurbStatus.CLEAR, result.status)
        assertEquals(nyc("2026-09-21", "08:00"), result.moveBy)
    }

    @Test
    fun `a suspension removes the day's window`() {
        val calendar = suspend("2026-09-14")
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-14", "08:45"), calendar)

        assertEquals(CurbStatus.SUSPENDED_TODAY, result.status)
        assertTrue(result.suspendedToday)
        // Monday is cancelled, so the next real sweeping is Thursday.
        assertEquals(nyc("2026-09-17", "08:00"), result.moveBy)
    }

    @Test
    fun `a suspension never hides an imminent window on another rule`() {
        // Monday sweeping is suspended, but a Monday evening no-standing rule still bites.
        val eveningRule = SignParser.parse("NO STANDING 6PM-8PM MON").single()
        val calendar = suspend("2026-09-14")

        val result = SweepSchedule.evaluate(
            listOf(monThuSweeping, eveningRule),
            nyc("2026-09-14", "17:30"),
            calendar,
        )

        assertEquals(CurbStatus.MOVE_WITHIN_HOUR, result.status)
        assertEquals(nyc("2026-09-14", "18:00"), result.moveBy)
    }

    @Test
    fun `no standing anytime is always restricted`() {
        val rule = SignParser.parse("NO STANDING ANYTIME").single()
        val result = SweepSchedule.evaluate(listOf(rule), nyc("2026-09-14", "12:00"), noSuspensions)

        assertEquals(CurbStatus.ALWAYS_RESTRICTED, result.status)
    }

    @Test
    fun `an unreadable sign never reports the curb as clear`() {
        val rule = SignParser.parse("AUTHORIZED VEHICLES ONLY").single()
        val result = SweepSchedule.evaluate(listOf(rule), nyc("2026-09-14", "12:00"), noSuspensions)

        assertEquals(CurbStatus.UNKNOWN, result.status)
    }

    @Test
    fun `a curb with no signs at all is unknown, not clear`() {
        val result = SweepSchedule.evaluate(emptyList(), nyc("2026-09-14", "12:00"), noSuspensions)

        assertEquals(CurbStatus.UNKNOWN, result.status)
    }

    @Test
    fun `metered parking alone does not make you move the car`() {
        val rule = SignParser.parse("2 HOUR PARKING 9AM-7PM EXCEPT SUNDAY").single()
        val result = SweepSchedule.evaluate(listOf(rule), nyc("2026-09-14", "12:00"), noSuspensions)

        assertEquals(CurbStatus.CLEAR, result.status)
    }

    @Test
    fun `the worst of several rules governs the colour`() {
        val sweeping = monThuSweeping
        val rushHour = SignParser.parse("NO STANDING 7AM-10AM MON THRU FRI").single()

        val result = SweepSchedule.evaluate(listOf(sweeping, rushHour), nyc("2026-09-15", "08:30"), noSuspensions)

        // Tuesday: no sweeping, but the rush-hour rule is open right now.
        assertEquals(CurbStatus.RESTRICTED_NOW, result.status)
        assertEquals(RegulationKind.NO_STANDING, result.governing?.kind)
    }

    @Test
    fun `spring forward keeps the sweeping at eight in the morning wall-clock`() {
        // 2026-03-08 is the spring-forward Sunday; 2026-03-09 is the Monday after it.
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-03-07", "12:00"), noSuspensions)

        val next = assertNotNull(result.moveBy)
        assertEquals(LocalDate.parse("2026-03-09"), next.toLocalDate())
        assertEquals(LocalTime.of(8, 0), next.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), next.offset) // EDT, not the EST we started in
    }

    @Test
    fun `fall back keeps the sweeping at eight in the morning wall-clock`() {
        // 2026-11-01 is the fall-back Sunday; 2026-11-02 is the Monday after it.
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-10-31", "12:00"), noSuspensions)

        val next = assertNotNull(result.moveBy)
        assertEquals(LocalDate.parse("2026-11-02"), next.toLocalDate())
        assertEquals(LocalTime.of(8, 0), next.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-5), next.offset) // EST
    }

    @Test
    fun `dates past the calendar's coverage are not treated as suspended`() {
        val shortCalendar = SuspensionCalendar(
            suspendedDates = setOf(LocalDate.parse("2026-09-14")),
            coverageStart = LocalDate.parse("2026-09-01"),
            coverageEnd = LocalDate.parse("2026-09-10"),
            fetchedAt = null,
        )

        val result = SweepSchedule.evaluate(listOf(monThuSweeping), nyc("2026-09-14", "08:45"), shortCalendar)

        assertEquals(CurbStatus.RESTRICTED_NOW, result.status)
    }

    @Test
    fun `evaluating from a non-NYC clock still uses NYC local time`() {
        // 12:45 UTC on Monday is 08:45 in New York — inside the window.
        val utc = ZonedDateTime.of(LocalDate.parse("2026-09-14"), LocalTime.parse("12:45"), ZoneOffset.UTC)
        val result = SweepSchedule.evaluate(listOf(monThuSweeping), utc, noSuspensions)

        assertEquals(CurbStatus.RESTRICTED_NOW, result.status)
    }
}
