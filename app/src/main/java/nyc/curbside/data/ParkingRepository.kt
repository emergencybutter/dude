package nyc.curbside.data

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import nyc.curbside.asp.AspRepository
import nyc.curbside.asp.CurbEvaluation
import nyc.curbside.asp.CurbMatch
import nyc.curbside.asp.CurbMatcher
import nyc.curbside.asp.LatLng
import nyc.curbside.asp.NYC
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.drive.SignalSource
import nyc.curbside.location.Fix
import nyc.curbside.location.FixQuality
import nyc.curbside.notify.MoveReminderScheduler
import nyc.curbside.notify.Notifications
import nyc.curbside.share.ShareCoordinator

/** A parking event with everything the UI needs to talk about it. */
data class ParkedCar(
    val event: ParkingEventEntity,
    val evaluation: CurbEvaluation?,
    val curbLabel: String?,
    /** True when the fix was too rough to tell which side of the street the car is on. */
    val needsSideConfirmation: Boolean,
)

@Singleton
class ParkingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ParkingEventDao,
    private val asp: AspRepository,
    private val reminders: MoveReminderScheduler,
    private val share: ShareCoordinator,
    private val settings: CurbsideSettings,
) {

    fun observeCurrent(): Flow<ParkingEventEntity?> = dao.observeCurrent()
    fun observeReceived(): Flow<List<ParkingEventEntity>> = dao.observeReceived()
    fun observeHistory(): Flow<List<ParkingEventEntity>> = dao.observeHistory()

    /**
     * Records where the car was left and everything that follows from it: the curb it is against,
     * when it has to move, the reminder, the notification, and the copy sent to the household.
     *
     * The previous parking is closed out rather than deleted, so the history survives.
     */
    suspend fun recordParking(
        fix: Fix,
        endedBy: SignalSource,
        now: ZonedDateTime = ZonedDateTime.now(NYC),
    ): ParkedCar = withContext(Dispatchers.IO) {
        val matches = asp.matchCurb(fix.point, fix.accuracyMeters, now)
        val best = matches.firstOrNull()
        val needsSide = CurbMatcher.needsSideConfirmation(matches.map { it.first }, fix.accuracyMeters)

        val event = ParkingEventEntity(
            id = UUID.randomUUID().toString(),
            parkedAt = fix.at.toEpochMilli(),
            latitude = fix.point.lat,
            longitude = fix.point.lon,
            accuracyMeters = fix.accuracyMeters,
            fixQuality = fix.quality.name,
            endedBy = endedBy.name,
            address = reverseGeocode(fix.point),
            curbSegmentId = best?.first?.curb?.segment?.id,
            curbSideConfirmed = best?.first?.onCorrectSide == true && !needsSide,
            moveByEpochMillis = best?.second?.moveBy?.toInstant()?.toEpochMilli(),
        )

        dao.clearCurrent(fix.at.toEpochMilli())
        dao.upsert(event)

        best?.second?.moveBy?.let { reminders.schedule(event.id, it) }
        Notifications.postParked(context, event, best?.second, needsSide)

        if (settings.readAutoShareEnabled()) share.enqueue(event.id)

        ParkedCar(
            event = event,
            evaluation = best?.second,
            curbLabel = best?.first?.label(),
            needsSideConfirmation = needsSide,
        )
    }

    /** The user drove away. Closes the current event and drops its reminder. */
    suspend fun clearCurrent(at: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        dao.current()?.let { reminders.cancel(it.id) }
        dao.clearCurrent(at.toEpochMilli())
    }

    /**
     * The user corrected the side of the street. Re-matches against the chosen curb and reschedules
     * the reminder, which is the whole reason the correction is offered.
     */
    suspend fun setCurb(eventId: String, curbSegmentId: String) = withContext(Dispatchers.IO) {
        val event = dao.byId(eventId) ?: return@withContext
        // Evaluated where the car actually stands: the block's rules are no longer uniform along
        // it, so the stretch matters to when the reminder fires.
        val curb = asp.curbById(curbSegmentId, at = LatLng(event.latitude, event.longitude))
            ?: return@withContext
        val moveBy = curb.evaluation.moveBy?.toInstant()?.toEpochMilli()

        dao.update(
            event.copy(
                curbSegmentId = curbSegmentId,
                curbSideConfirmed = true,
                moveByEpochMillis = moveBy,
            ),
        )
        curb.evaluation.moveBy?.let { reminders.schedule(eventId, it) } ?: reminders.cancel(eventId)
    }

    suspend fun setNote(eventId: String, note: String?) = withContext(Dispatchers.IO) {
        dao.byId(eventId)?.let { dao.update(it.copy(note = note?.takeIf(String::isNotBlank))) }
    }

    suspend fun setPhoto(eventId: String, uri: String?) = withContext(Dispatchers.IO) {
        dao.byId(eventId)?.let { dao.update(it.copy(photoUri = uri)) }
    }

    /** Drops a pin by hand, for the times detection missed or the user parked someone else's car. */
    suspend fun recordManual(point: LatLng, now: ZonedDateTime = ZonedDateTime.now(NYC)): ParkedCar =
        recordParking(
            fix = Fix(point, MANUAL_ACCURACY_METERS, FixQuality.MANUAL, now.toInstant()),
            endedBy = SignalSource.MANUAL,
            now = now,
        )

    /**
     * Street address for the pin, best effort.
     *
     * "Bergen St between 5th and 6th Ave" from the curb match is more useful than a house number,
     * but the house number is what the user recognises, so both are shown when available. A failure
     * here is not worth retrying: the coordinates are the real record.
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(point: LatLng): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context)
                .getFromLocation(point.lat, point.lon, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
    }

    private fun CurbMatch.label(): String =
        "${curb.segment.onStreet} between ${curb.segment.fromStreet} and ${curb.segment.toStreet}"

    private companion object {
        /** A hand-dropped pin is as accurate as the user's thumb — call it five metres. */
        const val MANUAL_ACCURACY_METERS = 5f
    }
}
