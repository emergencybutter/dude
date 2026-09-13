package nyc.curbside.asp

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Union St's south side, in the shape the app sees it: a hydrant zone by the corner and an
 * ordinary cleaning schedule down the rest of the block.
 */
class CurbExtentTest {

    /** A straight run east, about 175m long, at Brooklyn's latitude. */
    private val block = listOf(
        LatLng(40.6800, -74.0100),
        LatLng(40.6800, -74.0100 + 175.0 / (111_320.0 * 0.7585)),
    )

    private val cleaning = Regulation(
        kind = RegulationKind.STREET_CLEANING,
        days = setOf(DayOfWeek.FRIDAY),
        window = TimeWindow(LocalTime.of(8, 30), LocalTime.of(10, 0)),
        raw = "",
        extent = CurbExtent(26.5, 175.0),
    )

    /** 70ft to 87ft in: the five metres of kerb the anytime sign actually speaks for. */
    private val hydrant = Regulation(
        kind = RegulationKind.NO_STANDING,
        days = DayOfWeek.entries.toSet(),
        window = null,
        raw = "NO STANDING ANYTIME",
        daysInferred = true,
        extent = CurbExtent(21.3, 26.5),
    )

    private val curb = LocatedCurb(
        segment = CurbSegment(
            id = "union-south",
            onStreet = "UNION ST",
            fromStreet = "VAN BRUNT ST",
            toStreet = "COLUMBIA ST",
            side = StreetSide.SOUTH,
            regulations = listOf(hydrant, cleaning),
        ),
        geometry = block,
        sideSign = 1,
    )

    private fun sunday(time: String) =
        ZonedDateTime.of(LocalDate.parse("2026-09-13"), LocalTime.parse(time), NYC)

    @Test
    fun `parking down the block is graded on the cleaning schedule alone`() {
        val evaluation = SweepSchedule.evaluate(
            curb.segment.regulations,
            sunday("14:00"),
            SuspensionCalendar.EMPTY,
            SweepSchedule.HORIZON_DAYS,
            alongMeters = 100.0,
        )

        assertNotEquals(CurbStatus.ALWAYS_RESTRICTED, evaluation.status)
        assertEquals(false, evaluation.partiallyRestricted)
        assertEquals(nextFriday(), evaluation.moveBy)
    }

    @Test
    fun `parking in the hydrant zone is still never allowed`() {
        val evaluation = SweepSchedule.evaluate(
            curb.segment.regulations,
            sunday("14:00"),
            SuspensionCalendar.EMPTY,
            SweepSchedule.HORIZON_DAYS,
            alongMeters = 24.0,
        )

        assertEquals(CurbStatus.ALWAYS_RESTRICTED, evaluation.status)
    }

    @Test
    fun `a stretch no sign reaches is unknown, never clear`() {
        // The first twenty metres off the corner: no sign claims it, and absence of a sign has
        // never been permission.
        val evaluation = SweepSchedule.evaluate(
            curb.segment.regulations,
            sunday("14:00"),
            SuspensionCalendar.EMPTY,
            SweepSchedule.HORIZON_DAYS,
            alongMeters = 10.0,
        )

        assertEquals(CurbStatus.UNKNOWN, evaluation.status)
    }

    @Test
    fun `asking about the block as a whole still applies every rule on it`() {
        val evaluation = SweepSchedule.evaluate(
            curb.segment.regulations,
            sunday("14:00"),
            SuspensionCalendar.EMPTY,
        )

        // No position given, so the anytime sign is in scope and the old heuristic still catches it.
        assertTrue(evaluation.partiallyRestricted)
    }

    @Test
    fun `the block draws as three runs, not one colour`() {
        val runs = SweepSchedule.stretches(curb, sunday("14:00"))

        assertEquals(3, runs.size, runs.map { it.evaluation.status }.toString())
        assertEquals(CurbStatus.UNKNOWN, runs[0].evaluation.status)
        assertEquals(CurbStatus.ALWAYS_RESTRICTED, runs[1].evaluation.status)
        assertNotEquals(CurbStatus.ALWAYS_RESTRICTED, runs[2].evaluation.status)

        // Each run carries a drawable slice of the block, and together they cover it.
        assertTrue(runs.all { it.geometry.size >= 2 })
        assertEquals(0.0, runs.first().fromMeters, 0.01)
        assertEquals(175.0, runs.last().toMeters, 1.0)
    }

    @Test
    fun `an ordinary block is still a single run`() {
        val plain = curb.copy(
            segment = curb.segment.copy(regulations = listOf(cleaning.copy(extent = null))),
        )

        assertEquals(1, SweepSchedule.stretches(plain, sunday("14:00")).size)
    }

    @Test
    fun `a slice of the block measures the length that was asked for`() {
        val slice = Geo.slice(block, 40.0, 90.0)

        assertTrue(slice.size >= 2)
        assertEquals(50.0, Geo.lengthMeters(slice), 1.0)
    }

    @Test
    fun `a point projects to its distance along the block`() {
        val quarter = LatLng(40.6800, block[0].lon + (block[1].lon - block[0].lon) * 0.25)

        assertEquals(43.75, Geo.alongMeters(block, quarter)!!, 1.0)
    }

    @Test
    fun `an allowance asked at a spot ignores rules posted elsewhere`() {
        val allowance = ParkingWindow.allowance(
            curb.segment.regulations,
            sunday("14:00"),
            SuspensionCalendar.EMPTY,
            SweepSchedule.HORIZON_DAYS,
            alongMeters = 100.0,
        )

        assertTrue(allowance is ParkingAllowance.Free, "got $allowance")
        assertEquals(nextFriday(), allowance.until)
    }

    private fun nextFriday() =
        ZonedDateTime.of(LocalDate.parse("2026-09-18"), LocalTime.of(8, 30), NYC)
}
