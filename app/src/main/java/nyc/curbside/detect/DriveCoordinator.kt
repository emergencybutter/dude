package nyc.curbside.detect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.drive.DriveAction
import nyc.curbside.drive.DriveSignal
import nyc.curbside.drive.DriveStateMachine
import nyc.curbside.location.PassiveBreadcrumb
import nyc.curbside.location.ParkingCaptureWorker

/**
 * The Android half of drive detection: takes a signal, asks [DriveStateMachine] what it means, and
 * carries out the answer.
 *
 * Every entry point here runs from a broadcast receiver in a process that may have just been
 * created, so nothing is held in memory between calls — the state round-trips through DataStore
 * each time.
 */
@Singleton
class DriveCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: CurbsideSettings,
    private val breadcrumb: PassiveBreadcrumb,
) {

    private val machine = DriveStateMachine()

    suspend fun onSignal(signal: DriveSignal) {
        val before = settings.readDriveState()
        val (after, action) = machine.onSignal(before, signal)
        settings.writeDriveState(after)
        apply(action)
    }

    /** Called by [ParkCheckAlarmReceiver] when the debounce elapses. */
    suspend fun onParkCheckDue(now: Instant = Instant.now()) {
        val before = settings.readDriveState()
        val (after, action) = machine.onTimer(before, now)
        settings.writeDriveState(after)
        apply(action)
    }

    /**
     * Housekeeping for a drive that never ended, plus a chance to flush a confirmation whose alarm
     * the system dropped. Called on app launch and from the daily maintenance worker.
     */
    suspend fun reconcile(now: Instant = Instant.now()) {
        val state = settings.readDriveState()

        val (afterTimer, timerAction) = machine.onTimer(state, now)
        if (timerAction != DriveAction.None) {
            settings.writeDriveState(afterTimer)
            apply(timerAction)
            return
        }

        val (afterStale, staleAction) = machine.onStaleCheck(state, now)
        if (staleAction != DriveAction.None) {
            settings.writeDriveState(afterStale)
            apply(staleAction)
        }
    }

    private suspend fun apply(action: DriveAction) {
        when (action) {
            DriveAction.None -> Unit

            DriveAction.BeginDrive -> breadcrumb.start()

            is DriveAction.ScheduleParkCheck -> scheduleParkCheck(action.at)

            DriveAction.CancelParkCheck -> cancelParkCheck()

            DriveAction.DiscardShortTrip -> {
                cancelParkCheck()
                breadcrumb.stop()
            }

            is DriveAction.CapturePark -> {
                cancelParkCheck()
                breadcrumb.stop()
                enqueueCapture(action)
            }
        }
    }

    /**
     * Expedited work, not a foreground service.
     *
     * Since Android 12 an app cannot generally start a foreground service from the background, and
     * a broadcast receiver's ten seconds is not enough for a cold GPS fix. Expedited work is the
     * sanctioned path: WorkManager runs it immediately, promoting itself to a location-typed
     * foreground service where the platform requires one, and falls back to ordinary work if the
     * app has burned its expedited quota.
     */
    private fun enqueueCapture(action: DriveAction.CapturePark) {
        val request = OneTimeWorkRequestBuilder<ParkingCaptureWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    ParkingCaptureWorker.KEY_ENDED_BY to action.endedBy.name,
                    ParkingCaptureWorker.KEY_DROVE_FOR_SECONDS to action.droveFor.seconds,
                    ParkingCaptureWorker.KEY_ON_FOOT to action.confirmedOnFoot,
                ),
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ParkingCaptureWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * One alarm per drive, lasting under a minute.
     *
     * `setExactAndAllowWhileIdle` is used when the user has granted it: the whole point of the
     * debounce is that it expires at a known moment, and Doze deferring it by fifteen minutes would
     * pin the car wherever the phone happened to be when the alarm finally landed. Without the
     * permission the window form is close enough, since the walking transition usually arrives
     * first anyway.
     */
    private fun scheduleParkCheck(at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = at.toEpochMilli()
        val intent = parkCheckIntent()

        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canBeExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, trigger, WINDOW_MILLIS, intent)
        }
    }

    private fun cancelParkCheck() {
        context.getSystemService(AlarmManager::class.java)?.cancel(parkCheckIntent())
    }

    private fun parkCheckIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ParkCheckAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE = 4_202
        const val WINDOW_MILLIS = 60_000L
    }
}
