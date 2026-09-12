package nyc.curbside.asp

/** A curb segment with its geometry attached, as the matcher and the map layer both need it. */
data class LocatedCurb(
    val segment: CurbSegment,
    val geometry: List<LatLng>,
    /**
     * Which hand of [geometry] this curb is on: +1 right of the direction the line is drawn, -1
     * left. Both sides of a street share one centreline, so this is the only thing distinguishing
     * them.
     */
    val sideSign: Int,
)

data class CurbMatch(
    val curb: LocatedCurb,
    val distanceMeters: Double,
    /** True when the parked car is on the same hand of the street as this curb. */
    val onCorrectSide: Boolean,
)

/**
 * Decides which curb a parked car is against.
 *
 * The hard part is not distance, it is sides. Both sides of a block share a single centreline in
 * the city's data, so the two candidates are usually within a metre of each other in perpendicular
 * distance and have completely different cleaning schedules. A GPS fix good to 8m straddles a
 * 12m-wide street, so the side has to be decided by which hand of the centreline the fix falls
 * on — and the answer has to be discarded entirely when the fix is too poor to tell.
 */
object CurbMatcher {

    /** Beyond this, the car is not on this block at all. */
    const val MAX_MATCH_METERS: Double = 40.0

    /**
     * A fix worse than this cannot resolve a side on a typical NYC street, so the matcher reports
     * the block without committing to a side and the UI asks the user which side they parked on.
     */
    const val SIDE_CONFIDENCE_ACCURACY_METERS: Float = 12.0f

    /**
     * Ranks candidate curbs for a parked car.
     *
     * @param candidates typically everything the segment table holds within [MAX_MATCH_METERS] of
     *   the fix; the caller does the bounding-box query, this does the geometry.
     * @param accuracyMeters the reported accuracy of the fix, used only to decide whether the side
     *   is trustworthy. Pass [Float.MAX_VALUE] when unknown.
     */
    fun rank(
        point: LatLng,
        candidates: List<LocatedCurb>,
        accuracyMeters: Float = Float.MAX_VALUE,
    ): List<CurbMatch> {
        val sideIsTrustworthy = accuracyMeters <= SIDE_CONFIDENCE_ACCURACY_METERS

        return candidates.mapNotNull { curb ->
            val projection = Geo.project(point, curb.geometry) ?: return@mapNotNull null
            if (projection.distanceMeters > MAX_MATCH_METERS) return@mapNotNull null

            val correctSide = !sideIsTrustworthy ||
                projection.side == 0 ||
                projection.side == curb.sideSign

            CurbMatch(curb, projection.distanceMeters, correctSide)
        }.sortedWith(
            // The correct side always outranks a marginally closer wrong side: being 3m from the
            // curb you are not parked against is not a better answer than 4m from the one you are.
            compareByDescending<CurbMatch> { it.onCorrectSide }.thenBy { it.distanceMeters },
        )
    }

    /**
     * The single best guess, or null when nothing is close enough.
     *
     * Returns a match whose [CurbMatch.onCorrectSide] is false only when no correct-side candidate
     * exists at all, so callers can tell "I found your curb" from "I found the block but not the
     * side".
     */
    fun best(
        point: LatLng,
        candidates: List<LocatedCurb>,
        accuracyMeters: Float = Float.MAX_VALUE,
    ): CurbMatch? = rank(point, candidates, accuracyMeters).firstOrNull()

    /**
     * True when the two best candidates disagree about the schedule and the fix is not good enough
     * to choose between them. The UI turns this into a one-tap "which side?" prompt rather than
     * silently picking, because guessing wrong here is exactly the failure the app exists to avoid.
     */
    fun needsSideConfirmation(matches: List<CurbMatch>, accuracyMeters: Float): Boolean {
        if (accuracyMeters <= SIDE_CONFIDENCE_ACCURACY_METERS) return false
        if (matches.size < 2) return false
        val (first, second) = matches
        if (first.curb.segment.side == second.curb.segment.side) return false
        return first.curb.segment.regulations != second.curb.segment.regulations
    }
}
