package nyc.curbside.asp

import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nyc.curbside.data.RegulationCodec
import nyc.curbside.data.db.CurbSegmentDao
import nyc.curbside.data.db.CurbSegmentEntity

/** A curb ready to draw or reason about: geometry, rules, and its status at a given moment. */
data class EvaluatedCurb(
    val curb: LocatedCurb,
    /** The curb taken as a whole: every rule on it, wherever it applies. */
    val evaluation: CurbEvaluation,
    /**
     * The same curb broken into runs that each have one answer, for drawing. An ordinary block is
     * a single stretch; one with a hydrant zone or a bus stop is two or three.
     */
    val stretches: List<CurbStretch> = emptyList(),
)

/**
 * Everything the curb detail sheet shows for one tapped curb.
 *
 * [at] is carried along because the map asks about scrubbed times as well as the present, and a
 * sheet that computed "for how long" against a fresher clock than the one that produced
 * [allowance] would drift from the answer it is displaying.
 */
data class CurbDetail(
    val curb: EvaluatedCurb,
    val allowance: ParkingAllowance,
    val at: ZonedDateTime,
    /**
     * Where along the curb the question was asked, in metres. Set when the user tapped a spot, so
     * the answer is about that stretch rather than the worst of the whole block; null when the
     * whole curb is being described.
     */
    val alongMeters: Double? = null,
    /**
     * The days ahead when this curb's cleaning is called off, and what for.
     *
     * Only the ones that land on a day it would have been cleaned: every other holiday in the city
     * is noise here.
     */
    val suspensions: List<SuspendedDay> = emptyList(),
)

/**
 * Reads curb data out of the local database and grades it.
 *
 * There is no network in this class. The whole sign dataset is installed once by
 * [nyc.curbside.asp.AspDatasetInstaller] and read from SQLite thereafter, so the map works on the
 * subway, in a garage, and on a dead battery's last five percent without a radio ever waking up.
 */
@Singleton
class AspRepository @Inject constructor(
    private val dao: CurbSegmentDao,
    private val suspensions: SuspensionRepository,
) {

    /**
     * Every curb overlapping [box], graded for [now].
     *
     * @param limit a hard cap so a zoomed-out pan cannot load the borough. The map layer refuses to
     *   draw below its minimum zoom anyway; this is the backstop.
     */
    suspend fun curbsIn(
        box: BoundingBox,
        now: ZonedDateTime = ZonedDateTime.now(NYC),
        limit: Int = VIEWPORT_LIMIT,
    ): List<EvaluatedCurb> = withContext(Dispatchers.IO) {
        val calendar = suspensions.current()
        dao.inBox(box.minLat, box.minLon, box.maxLat, box.maxLon, limit)
            .map { entity ->
                val located = entity.toLocatedCurb()
                EvaluatedCurb(
                    curb = located,
                    evaluation = SweepSchedule.evaluate(located.segment.regulations, now, calendar),
                    stretches = SweepSchedule.stretches(located, now, calendar),
                )
            }
    }

    /**
     * Which curb a car at [point] is parked against.
     *
     * Returns the ranked candidates rather than one answer so the caller can tell the user "I think
     * you're on the north side" and offer the other side in one tap when the fix was too rough to
     * be sure. See [CurbMatcher.needsSideConfirmation].
     */
    suspend fun matchCurb(
        point: LatLng,
        accuracyMeters: Float,
        now: ZonedDateTime = ZonedDateTime.now(NYC),
    ): List<Pair<CurbMatch, CurbEvaluation>> = withContext(Dispatchers.IO) {
        val calendar = suspensions.current()
        val box = BoundingBox.around(point, CurbMatcher.MAX_MATCH_METERS + SEARCH_PADDING_METERS)
        val candidates = dao
            .inBox(box.minLat, box.minLon, box.maxLat, box.maxLon, MATCH_LIMIT)
            .map { it.toLocatedCurb() }

        CurbMatcher.rank(point, candidates, accuracyMeters).map { match ->
            // Where along the block the car actually stands, so a hydrant zone forty metres away
            // is not the reason a reminder fires.
            val along = Geo.alongMeters(match.curb.geometry, point)
            match to SweepSchedule.evaluate(
                match.curb.segment.regulations,
                now,
                calendar,
                SweepSchedule.HORIZON_DAYS,
                along,
            )
        }
    }

    suspend fun curbById(
        id: String,
        now: ZonedDateTime = ZonedDateTime.now(NYC),
        at: LatLng? = null,
    ): EvaluatedCurb? = curbDetail(id, now, at)?.curb

    /**
     * One curb, graded and with its next free span worked out: what the map's detail sheet needs
     * from a tap.
     *
     * Reads through the database rather than the viewport the tap came from, so the sheet survives
     * the user panning the curb off screen.
     */
    /**
     * @param at a point on the curb the caller is asking about — the spot the user tapped, or where
     *   the car stands. Rules governing other stretches of the block are left out of the answer.
     */
    suspend fun curbDetail(
        id: String,
        now: ZonedDateTime = ZonedDateTime.now(NYC),
        at: LatLng? = null,
    ): CurbDetail? = withContext(Dispatchers.IO) {
        val entity = dao.byId(id) ?: return@withContext null
        val calendar = suspensions.current()
        val located = entity.toLocatedCurb()
        val regulations = located.segment.regulations
        val along = at?.let { Geo.alongMeters(located.geometry, it) }

        CurbDetail(
            curb = EvaluatedCurb(
                curb = located,
                evaluation = SweepSchedule.evaluate(
                    regulations,
                    now,
                    calendar,
                    SweepSchedule.HORIZON_DAYS,
                    along,
                ),
                stretches = SweepSchedule.stretches(located, now, calendar),
            ),
            allowance = ParkingWindow.allowance(
                regulations,
                now,
                calendar,
                SweepSchedule.HORIZON_DAYS,
                along,
            ),
            at = now,
            alongMeters = along,
            suspensions = SweepSchedule.suspensionsAhead(
                regulations.filter { it.governs(along) },
                now,
                calendar,
            ),
        )
    }

    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) { dao.count() > 0 }

    private fun CurbSegmentEntity.toLocatedCurb() = LocatedCurb(
        segment = CurbSegment(
            id = id,
            onStreet = onStreet,
            fromStreet = fromStreet,
            toStreet = toStreet,
            side = StreetSide.parse(side),
            regulations = RegulationCodec.decode(rulesJson),
        ),
        geometry = PolylineCodec.decode(geometry),
        sideSign = sideSign,
    )

    private companion object {
        /**
         * At the minimum drawing zoom a viewport holds a few hundred blocks; four thousand curbs is
         * comfortably above that and still evaluates in a few milliseconds.
         */
        const val VIEWPORT_LIMIT = 4_000
        const val MATCH_LIMIT = 64
        const val SEARCH_PADDING_METERS = 10.0
    }
}
