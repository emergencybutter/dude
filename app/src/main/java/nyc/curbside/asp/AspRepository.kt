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
    val evaluation: CurbEvaluation,
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
                EvaluatedCurb(located, SweepSchedule.evaluate(located.segment.regulations, now, calendar))
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
            match to SweepSchedule.evaluate(match.curb.segment.regulations, now, calendar)
        }
    }

    suspend fun curbById(id: String, now: ZonedDateTime = ZonedDateTime.now(NYC)): EvaluatedCurb? =
        withContext(Dispatchers.IO) {
            val entity = dao.byId(id) ?: return@withContext null
            val located = entity.toLocatedCurb()
            EvaluatedCurb(located, SweepSchedule.evaluate(located.segment.regulations, now, suspensions.current()))
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
