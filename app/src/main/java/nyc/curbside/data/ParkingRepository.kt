package nyc.curbside.data

import android.content.Context
import android.location.Geocoder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
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
import nyc.curbside.asp.Geo
import nyc.curbside.asp.LatLng
import nyc.curbside.asp.NYC
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.data.db.ParkingEventEntity
import nyc.curbside.drive.DrivePlausibility
import nyc.curbside.drive.SignalSource
import nyc.curbside.drive.VehicleEvidence
import nyc.curbside.drive.VehicleIdentification
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
    private val vehicles: VehicleRepository,
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
    ): ParkedCar? = withContext(Dispatchers.IO) {
        // Did a car go anywhere? Activity recognition sometimes calls a walk a vehicle trip, and
        // the pin that follows lands wherever the walk ended — replacing a spot that was right.
        val origin = settings.readDriveOrigin()
        val travelled = origin?.let { Geo.haversineMeters(LatLng(it.latitude, it.longitude), fix.point) }
        val took = origin?.let { Duration.between(it.at, fix.at) }
        if (DrivePlausibility.looksLikeWalking(travelled, took, endedBy)) {
            vehicles.forgetDrive()
            return@withContext null
        }

        // A fix that cannot name a block gets no rules hung on it. Matching a curb four hundred
        // metres from the car would produce a confident sweeping time for a street it is not on,
        // and a reminder to move it from a space it never occupied.
        val locatable = fix.quality != FixQuality.COARSE
        val matches = if (locatable) asp.matchCurb(fix.point, fix.accuracyMeters, now) else emptyList()
        val best = matches.firstOrNull()
        val needsSide = CurbMatcher.needsSideConfirmation(matches.map { it.first }, fix.accuracyMeters)

        // Which car this was, from the stereo if it spoke and from where the drive began if it did
        // not. An Ask is recorded as an unnamed parking; the home screen puts the question.
        val identified = vehicles.identifyDrive() as? VehicleIdentification.Identified
        vehicles.forgetDrive()

        val event = ParkingEventEntity(
            id = UUID.randomUUID().toString(),
            parkedAt = fix.at.toEpochMilli(),
            latitude = fix.point.lat,
            longitude = fix.point.lon,
            accuracyMeters = fix.accuracyMeters,
            fixQuality = fix.quality.name,
            endedBy = endedBy.name,
            vehicleId = identified?.vehicleId,
            vehicleEvidence = identified?.evidence?.name,
            address = reverseGeocode(fix.point),
            curbSegmentId = best?.first?.curb?.segment?.id,
            curbSideConfirmed = best?.first?.onCorrectSide == true && !needsSide,
            moveByEpochMillis = best?.second?.moveBy?.toInstant()?.toEpochMilli(),
        )

        dao.upsert(event)
        // This car's previous spot is now history — including one a partner recorded, since the car
        // cannot be in two streets at once.
        dao.clearCurrentForVehicle(event.vehicleId, fix.at.toEpochMilli(), exceptId = event.id)

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

    /**
     * The user drove one particular car away.
     *
     * Per event rather than per person: with two cars parked, "I moved it" on one card must not
     * quietly close out the other one as well.
     */
    suspend fun clearEvent(eventId: String, at: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        reminders.cancel(eventId)
        dao.clearEvent(eventId, at.toEpochMilli())
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

    /**
     * The user saying which car a parking was, when the app could not tell.
     *
     * Supersedes wherever that car was before — answering the question is exactly the moment the
     * old record becomes wrong — and shares the spot if auto-share is on, since a parking nobody
     * could name was not worth sending until now.
     */
    suspend fun assignVehicle(eventId: String, vehicleId: String) = withContext(Dispatchers.IO) {
        val event = dao.byId(eventId) ?: return@withContext
        dao.assignVehicle(eventId, vehicleId, VehicleEvidence.STATED.name)
        dao.clearCurrentForVehicle(vehicleId, event.parkedAt, exceptId = eventId)
        if (event.sharedAt == null && !event.shareSuppressed && settings.readAutoShareEnabled()) {
            share.enqueue(eventId)
        }
    }

    fun observeActive(): Flow<List<ParkingEventEntity>> = dao.observeActive()

    suspend fun setNote(eventId: String, note: String?) = withContext(Dispatchers.IO) {
        dao.byId(eventId)?.let { dao.update(it.copy(note = note?.takeIf(String::isNotBlank))) }
    }

    suspend fun setPhoto(eventId: String, uri: String?) = withContext(Dispatchers.IO) {
        dao.byId(eventId)?.let { dao.update(it.copy(photoUri = uri)) }
    }

    /** Drops a pin by hand, for the times detection missed or the user parked someone else's car. */
    suspend fun recordManual(point: LatLng, now: ZonedDateTime = ZonedDateTime.now(NYC)): ParkedCar =
        // Never null: a pin the user placed by hand is not second-guessed, and the walking check
        // only ever doubts activity recognition.
        recordParking(
            fix = Fix(point, MANUAL_ACCURACY_METERS, FixQuality.MANUAL, now.toInstant()),
            endedBy = SignalSource.MANUAL,
            now = now,
        )!!

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
