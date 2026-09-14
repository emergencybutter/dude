package nyc.curbside.share

import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import nyc.curbside.R
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.data.VehicleRepository
import nyc.curbside.data.db.ParkingEventDao
import nyc.curbside.data.db.ParkingEventEntity

/**
 * Gets a parking event to the other phone.
 *
 * Two paths, deliberately separate:
 *
 * - **Auto-share** goes through the household channel: encrypted, silent, and only when the user
 *   has turned it on and paired a device. Firestore queues offline writes itself; the worker exists
 *   for the cases Firestore cannot cover, like being signed out or having no household yet when the
 *   car was parked in a basement garage.
 * - **Manual share** is an ordinary Android share sheet with a maps link, which works with anyone,
 *   needs no account, and is what you want when you are texting a friend rather than your partner.
 */
@Singleton
class ShareCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CurbsideSettings,
) {

    /** Queues [eventId] for household delivery. Idempotent per event. */
    suspend fun enqueue(eventId: String) {
        if (settings.readHouseholdId() == null) return

        val request = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(workDataOf(ShareWorker.KEY_EVENT_ID to eventId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("${ShareWorker.NAME}-$eventId", ExistingWorkPolicy.KEEP, request)
    }

    /** Retries anything that never made it. Called from the daily maintenance worker. */
    suspend fun enqueuePending(dao: ParkingEventDao) {
        dao.pendingShare().forEach { enqueue(it.id) }
    }

    /**
     * The share sheet. A `geo:` URI would only open a map app, so the text carries a Google Maps
     * link as well — that is what survives being pasted into a message and opened on an iPhone.
     */
    fun manualShareIntent(event: ParkingEventEntity): Intent {
        val coords = "${event.latitude},${event.longitude}"
        val link = "https://maps.google.com/?q=$coords"
        val where = event.address?.let { "$it\n" } ?: ""
        val text = context.getString(R.string.share_text, where, link)

        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
            },
            context.getString(R.string.share_title),
        )
    }

    private companion object {
        val BACKOFF: Duration = Duration.ofSeconds(30)
    }
}

@HiltWorker
class ShareWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: ParkingEventDao,
    private val household: HouseholdRepository,
    private val vehicles: VehicleRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()

        val event = dao.byId(eventId) ?: return Result.failure()

        // Respect a later opt-out: the user may have suppressed this event after it was queued.
        if (event.shareSuppressed) return Result.success()
        if (event.sharedAt != null) return Result.success()

        val payload = SharedParkingPayload(
            latitude = event.latitude,
            longitude = event.longitude,
            accuracyMeters = event.accuracyMeters,
            parkedAtEpochMillis = event.parkedAt,
            address = event.address,
            note = event.note,
            moveByEpochMillis = event.moveByEpochMillis,
            vehicleId = event.vehicleId,
            vehicleLabel = vehicles.byId(event.vehicleId)?.name,
        )

        return if (household.publish(eventId, payload)) {
            dao.markShared(eventId, Instant.now().toEpochMilli())
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val NAME = "share"
        const val KEY_EVENT_ID = "event_id"
    }
}
