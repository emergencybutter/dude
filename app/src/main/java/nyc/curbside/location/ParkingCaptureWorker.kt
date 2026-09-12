package nyc.curbside.location

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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

        val fix = fixer.capture()
        if (fix == null) {
            // No fix and no breadcrumb. Retrying later would pin the car wherever the phone is by
            // then, which is worse than nothing, so tell the user and let them drop a pin.
            Notifications.postCaptureFailed(applicationContext)
            return Result.failure()
        }

        parking.recordParking(fix, endedBy)
        return Result.success()
    }

    companion object {
        const val NAME = "parking-capture"
        const val KEY_ENDED_BY = "ended_by"
        const val KEY_DROVE_FOR_SECONDS = "drove_for_seconds"
        const val KEY_ON_FOOT = "on_foot"
    }
}
