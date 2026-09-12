package nyc.curbside.asp

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CurbMatcherTest {

    /** One block of a west-to-east street, shared by both curbs. */
    private val centreline = listOf(
        LatLng(40.68210, -73.98010),
        LatLng(40.68212, -73.97880),
    )

    private val mondaySweeping = SignParser
        .parse("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM MON")
        .single()

    private val tuesdaySweeping = SignParser
        .parse("NO PARKING (SANITATION BROOM SYMBOL) 8-9:30AM TUES")
        .single()

    private fun curb(id: String, side: StreetSide, sideSign: Int, rules: List<Regulation>) = LocatedCurb(
        segment = CurbSegment(
            id = id,
            onStreet = "BERGEN ST",
            fromStreet = "5 AV",
            toStreet = "6 AV",
            side = side,
            regulations = rules,
        ),
        geometry = centreline,
        sideSign = sideSign,
    )

    // The line runs west to east, so its right hand is the south side of the street.
    private val southCurb = curb("south", StreetSide.SOUTH, sideSign = 1, rules = listOf(mondaySweeping))
    private val northCurb = curb("north", StreetSide.NORTH, sideSign = -1, rules = listOf(tuesdaySweeping))
    private val bothSides = listOf(southCurb, northCurb)

    @Test
    fun `a good fix on the south side picks the south curb`() {
        val parked = LatLng(40.68203, -73.97945) // ~8m south of the centreline

        val best = assertNotNull(CurbMatcher.best(parked, bothSides, accuracyMeters = 6f))

        assertEquals("south", best.curb.segment.id)
        assertTrue(best.onCorrectSide)
    }

    @Test
    fun `a good fix on the north side picks the north curb`() {
        val parked = LatLng(40.68219, -73.97945)

        val best = assertNotNull(CurbMatcher.best(parked, bothSides, accuracyMeters = 6f))

        assertEquals("north", best.curb.segment.id)
        assertTrue(best.onCorrectSide)
    }

    @Test
    fun `the correct side wins even when the wrong side is marginally closer`() {
        // Perpendicular distance is identical for both curbs — they share a centreline — so the
        // ordering has to come from the side test, not from distance.
        val parked = LatLng(40.68203, -73.97945)

        val ranked = CurbMatcher.rank(parked, listOf(northCurb, southCurb), accuracyMeters = 5f)

        assertEquals("south", ranked.first().curb.segment.id)
        assertFalse(ranked.last().onCorrectSide)
    }

    @Test
    fun `a poor fix trusts no side and both curbs stay in play`() {
        val parked = LatLng(40.68203, -73.97945)

        val ranked = CurbMatcher.rank(parked, bothSides, accuracyMeters = 30f)

        assertTrue(ranked.all { it.onCorrectSide }, "a 30m fix cannot resolve a side either way")
        assertTrue(CurbMatcher.needsSideConfirmation(ranked, accuracyMeters = 30f))
    }

    @Test
    fun `a good fix never asks the user which side they parked on`() {
        val parked = LatLng(40.68203, -73.97945)
        val ranked = CurbMatcher.rank(parked, bothSides, accuracyMeters = 5f)

        assertFalse(CurbMatcher.needsSideConfirmation(ranked, accuracyMeters = 5f))
    }

    @Test
    fun `sides sharing a schedule need no confirmation even on a poor fix`() {
        val sameRules = listOf(
            southCurb,
            curb("north", StreetSide.NORTH, sideSign = -1, rules = listOf(mondaySweeping)),
        )
        val ranked = CurbMatcher.rank(LatLng(40.68203, -73.97945), sameRules, accuracyMeters = 30f)

        assertFalse(CurbMatcher.needsSideConfirmation(ranked, accuracyMeters = 30f))
    }

    @Test
    fun `a car a block away matches nothing`() {
        val parked = LatLng(40.68350, -73.97945) // ~155m north

        assertNull(CurbMatcher.best(parked, bothSides, accuracyMeters = 5f))
    }

    @Test
    fun `no candidates yields no match`() {
        assertNull(CurbMatcher.best(LatLng(40.68203, -73.97945), emptyList()))
    }
}
