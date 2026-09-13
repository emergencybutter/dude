package nyc.curbside.asp

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ParkingWindowTest {

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

    private fun free(allowance: ParkingAllowance): ParkingAllowance.Free {
        assertTrue(allowance is ParkingAllowance.Free, "expected a free span, got $allowance")
        return allowance
    }

    @Test
    fun `a clear curb may be parked on now, until the next sweeping`() {
        // Sunday afternoon; the next window is Monday morning.
        val result = free(
            ParkingWindow.allowance(listOf(monThuSweeping), nyc("2026-09-13", "14:00"), noSuspensions),
        )

        assertTrue(result.isImmediate)
        assertNull(result.from)
        assertEquals(nyc("2026-09-14", "08:00"), result.until)
    }

    @Test
    fun `a curb being swept right now frees up when the window closes`() {
        // Monday 08:45, mid-sweep. To a driver looking for a spot this is not "no", it is "in 45
        // minutes" — and then it is good until Thursday.
        val result = free(
            ParkingWindow.allowance(listOf(monThuSweeping), nyc("2026-09-14", "08:45"), noSuspensions),
        )

        assertEquals(nyc("2026-09-14", "09:30"), result.from)
        assertEquals(nyc("2026-09-17", "08:00"), result.until)
        assertEquals(false, result.isImmediate)
    }

    @Test
    fun `the duration of a future span is measured from when it starts, not from now`() {
        val now = nyc("2026-09-14", "08:45")
        val result = free(ParkingWindow.allowance(listOf(monThuSweeping), now, noSuspensions))

        // 09:30 Monday to 08:00 Thursday, not 08:45 Monday to 08:00 Thursday.
        assertEquals(Duration.ofHours(70).plusMinutes(30), result.duration(now))
    }

    @Test
    fun `back to back windows are one span, so the gap between them is never offered`() {
        val eightToNineThirty = Regulation(
            kind = RegulationKind.NO_PARKING,
            days = setOf(DayOfWeek.MONDAY),
            window = TimeWindow(LocalTime.of(8, 0), LocalTime.of(9, 30)),
            raw = "",
        )
        val nineThirtyToEleven = eightToNineThirty.copy(
            window = TimeWindow(LocalTime.of(9, 30), LocalTime.of(11, 0)),
        )

        val result = free(
            ParkingWindow.allowance(
                listOf(eightToNineThirty, nineThirtyToEleven),
                nyc("2026-09-14", "08:45"),
                noSuspensions,
            ),
        )

        // 09:30 is when the first rule lapses and the second begins. Offering it would be a lie.
        assertEquals(nyc("2026-09-14", "11:00"), result.from)
    }

    @Test
    fun `overlapping windows collapse to the later end`() {
        val morning = Regulation(
            kind = RegulationKind.NO_PARKING,
            days = setOf(DayOfWeek.MONDAY),
            window = TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)),
            raw = "",
        )
        val overlapping = morning.copy(window = TimeWindow(LocalTime.of(9, 0), LocalTime.of(12, 0)))

        val result = free(
            ParkingWindow.allowance(
                listOf(morning, overlapping),
                nyc("2026-09-14", "08:45"),
                noSuspensions,
            ),
        )

        assertEquals(nyc("2026-09-14", "12:00"), result.from)
    }

    @Test
    fun `a suspended cleaning day is not a reason to move`() {
        // Monday's sweeping is called off, so the answer runs through to Thursday.
        val result = free(
            ParkingWindow.allowance(
                listOf(monThuSweeping),
                nyc("2026-09-13", "14:00"),
                suspend("2026-09-14"),
            ),
        )

        assertTrue(result.isImmediate)
        assertEquals(nyc("2026-09-17", "08:00"), result.until)
    }

    @Test
    fun `no standing at any hour is never, not a span`() {
        val noStanding = Regulation(
            kind = RegulationKind.NO_STANDING,
            days = DayOfWeek.entries.toSet(),
            window = null,
            raw = "NO STANDING ANYTIME",
        )

        assertEquals(
            ParkingAllowance.Never,
            ParkingWindow.allowance(listOf(noStanding), nyc("2026-09-13", "14:00"), noSuspensions),
        )
    }

    @Test
    fun `a curb with no signs is unknown, not free`() {
        assertEquals(
            ParkingAllowance.Unknown,
            ParkingWindow.allowance(emptyList(), nyc("2026-09-13", "14:00"), noSuspensions),
        )
    }

    @Test
    fun `a meter caps the stay but does not restrict the curb`() {
        val metered = Regulation(
            kind = RegulationKind.TIME_LIMITED,
            days = setOf(DayOfWeek.MONDAY),
            window = TimeWindow(LocalTime.of(9, 0), LocalTime.of(19, 0)),
            raw = "2 HOUR METERED PARKING 9AM-7PM MON",
        )

        // Advisory rules are listed on the sheet but never make the curb illegal, so there is no
        // moment the car must be gone by.
        val result = free(
            ParkingWindow.allowance(listOf(metered), nyc("2026-09-14", "10:00"), noSuspensions),
        )

        assertTrue(result.isImmediate)
        assertNull(result.until)
        assertNull(result.duration(nyc("2026-09-14", "10:00")))
    }

    /**
     * The Union St bug: one bus-stop sign on a block that is otherwise ordinary alternate-side
     * parking condemned the whole block. Half the curbs in the city's data carry one of these.
     */
    @Test
    fun `an anytime sign beside a cleaning schedule restricts part of the block, not all of it`() {
        val busStop = Regulation(
            kind = RegulationKind.NO_STANDING,
            days = DayOfWeek.entries.toSet(),
            window = null,
            raw = "NO STANDING ANYTIME",
            daysInferred = true,
        )

        val allowance = ParkingWindow.allowance(
            listOf(busStop, monThuSweeping),
            nyc("2026-09-13", "14:00"),
            noSuspensions,
        )

        // Not Never: the block is swept on Mondays and Thursdays, which only makes sense if cars
        // are expected to be on it the rest of the week.
        val result = free(allowance)
        assertTrue(result.isImmediate)
        assertEquals(nyc("2026-09-14", "08:00"), result.until)

        val evaluation = SweepSchedule.evaluate(
            listOf(busStop, monThuSweeping),
            nyc("2026-09-13", "14:00"),
            noSuspensions,
        )
        assertTrue(evaluation.partiallyRestricted, "the bus stop still has to be reported")
        // 18 hours to Monday's sweeping: graded on the cleaning schedule, as any ordinary curb is.
        assertEquals(CurbStatus.MOVE_IN_TWO_DAYS, evaluation.status)
    }

    @Test
    fun `an anytime sign with nothing to contradict it still condemns the curb`() {
        val noStanding = Regulation(
            kind = RegulationKind.NO_STANDING,
            days = DayOfWeek.entries.toSet(),
            window = null,
            raw = "NO STANDING ANYTIME",
            daysInferred = true,
        )

        assertEquals(
            ParkingAllowance.Never,
            ParkingWindow.allowance(listOf(noStanding), nyc("2026-09-13", "14:00"), noSuspensions),
        )
        assertEquals(
            CurbStatus.ALWAYS_RESTRICTED,
            SweepSchedule.evaluate(listOf(noStanding), nyc("2026-09-13", "14:00"), noSuspensions).status,
        )
    }

    @Test
    fun `a meter is enough to contradict an anytime sign`() {
        val busStop = Regulation(
            kind = RegulationKind.NO_STANDING,
            days = DayOfWeek.entries.toSet(),
            window = null,
            raw = "NO STANDING ANYTIME",
            daysInferred = true,
        )
        val metered = Regulation(
            kind = RegulationKind.TIME_LIMITED,
            days = setOf(DayOfWeek.MONDAY),
            window = TimeWindow(LocalTime.of(9, 0), LocalTime.of(19, 0)),
            raw = "2 HOUR METERED PARKING 9AM-7PM MON",
        )

        val evaluation = SweepSchedule.evaluate(
            listOf(busStop, metered),
            nyc("2026-09-14", "10:00"),
            noSuspensions,
        )

        // Nobody bills for a curb that may never be stood on.
        assertEquals(CurbStatus.CLEAR, evaluation.status)
        assertTrue(evaluation.partiallyRestricted)
    }

    @Test
    fun `a curb whose only rule is unparsed stays unknown`() {
        val unparsed = Regulation(
            kind = RegulationKind.OTHER,
            days = emptySet(),
            window = null,
            raw = "SOMETHING THE PARSER COULD NOT READ",
        )

        assertEquals(
            ParkingAllowance.Unknown,
            ParkingWindow.allowance(listOf(unparsed), nyc("2026-09-13", "14:00"), noSuspensions),
        )
    }
}
