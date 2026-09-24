package nyc.curbside.location

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import nyc.curbside.CurbsideLog
import nyc.curbside.data.ParkingRepository
import nyc.curbside.drive.SignalSource
import nyc.curbside.notify.Notifications

/**
 * Takes the parking fix and records the event.
 *
 * Runs as expedited work so it starts within seconds of the drive ending and is allowed to touch
 * location from the background. It is the only component in the app that ever asks for a live
 * position, it runs at most a few times a day, and it is finished inside half a minute.
 */
@HiltWorker
class ParkingCaptureWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val fixer: LocationFixer,
    private val parking: ParkingRepository,
    private val settings: nyc.curbside.data.CurbsideSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = Notifications.capturingNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            ForegroundInfo(Notifications.CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val endedBy = inputData.getString(KEY_ENDED_BY)
            ?.let { runCatching { SignalSource.valueOf(it) }.getOrNull() }
            ?: SignalSource.ACTIVITY_RECOGNITION

        // How long the drive actually lasted, as the state machine measured it. The plausibility
        // check needs this rather than a duration derived from the origin fix: that fix is whatever
        // the phone had cached and may be a quarter of an hour old, which stretches the apparent
        // trip time and makes a real drive look like walking pace.
        val droveFor = inputData.getLong(KEY_DROVE_FOR_SECONDS, -1L)
            .takeIf { it >= 0 }
            ?.let(Duration::ofSeconds)

        // When the car stopped, as against when the machine finished deciding it had. For a
        // zero-debounce source these are the same instant; for Android Auto they are twenty
        // seconds and a walk apart.
        val endedAt = inputData.getLong(KEY_ENDED_AT, -1L)
            .takeIf { it >= 0 }
            ?.let(Instant::ofEpochMilli)
            ?: Instant.now()

        // The position held when the end signal arrived, if there was one. Consumed here whatever
        // happens next, so a snapshot can never outlive its own drive and be spent on the following
        // one — a capture with a stale spot on it is worse than a capture with none.
        val snapshot = settings.readParkSnapshot()
        settings.clearParkSnapshot()

        val fix = fixer.capture(
            endedAt = endedAt,
            snapshot = snapshot?.let {
                Fix(
                    point = nyc.curbside.asp.LatLng(it.latitude, it.longitude),
                    accuracyMeters = it.accuracyMeters,
                    quality = FixQuality.RECENT_CACHED,
                    at = it.at,
                )
            },
        )
        if (fix == null) {
            CurbsideLog.w("capture failed: no usable fix and no breadcrumb to fall back on")
            // No fix and no breadcrumb. Retrying later would pin the car wherever the phone is by
            // then, which is worse than nothing, so tell the user and let them drop a pin.
            Notifications.postCaptureFailed(applicationContext)
            return Result.failure()
        }

        // Null means the drive was judged never to have happened — a walk that activity
        // recognition called a car trip. The previous spot, which is still the right one, stands.
        CurbsideLog.i("capture got a ${fix.quality} fix, accurate to ${fix.accuracyMeters.toInt()}m")
        parking.recordParking(fix, endedBy, droveFor = droveFor)
        return Result.success()
    }

    companion object {
        const val NAME = "parking-capture"
        const val KEY_ENDED_BY = "ended_by"
        const val KEY_DROVE_FOR_SECONDS = "drove_for_seconds"
        const val KEY_ON_FOOT = "on_foot"
        const val KEY_ENDED_AT = "ended_at"
    }
}
