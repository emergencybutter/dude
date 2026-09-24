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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import nyc.curbside.CurbsideLog
import nyc.curbside.data.Breadcrumb
import nyc.curbside.data.CurbsideSettings
import nyc.curbside.drive.DriveAction
import nyc.curbside.drive.DriveSignal
import nyc.curbside.drive.DriveState
import nyc.curbside.drive.DriveStateMachine
import nyc.curbside.location.Fix
import nyc.curbside.location.LocationFixer
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
    private val fixer: LocationFixer,
) {

    private val machine = DriveStateMachine()

    suspend fun onSignal(signal: DriveSignal) {
        val before = settings.readDriveState()
        val (after, action) = machine.onSignal(before, signal)
        settings.writeDriveState(after)
        // The one line worth having above all others: what came in, what the machine made of it,
        // and what it decided. Every missed parking is a question about this line.
        CurbsideLog.i(
            "${signal.source} ${signal.kind}: ${before.phase} -> ${after.phase} — " +
                describe(action, before, signal.at),
        )
        apply(action)
    }

    /** Called by [ParkCheckAlarmReceiver] when the debounce elapses. */
    suspend fun onParkCheckDue(now: Instant = Instant.now()) {
        val before = settings.readDriveState()
        val (after, action) = machine.onTimer(before, now)
        settings.writeDriveState(after)
        CurbsideLog.i("park check due: ${before.phase} -> ${after.phase} — ${describe(action, before, now)}")
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
            CurbsideLog.i("reconcile picked up a dropped park check — ${describe(timerAction, state, now)}")
            apply(timerAction)
            return
        }

        val (afterStale, staleAction) = machine.onStaleCheck(state, now)
        if (staleAction != DriveAction.None) {
            settings.writeDriveState(afterStale)
            CurbsideLog.i("reconcile — ${describe(staleAction, state, now)}")
            apply(staleAction)
        }
    }

    /**
     * Says what an action means in the terms the reader cares about, and above all *why* a trip was
     * thrown away — the discard is the decision that looks like the app being broken when it is not.
     */
    private fun describe(action: DriveAction, before: DriveState, at: Instant): String {
        val drove = before.driveStartedAt?.let { Duration.between(it, at) }
        return when (action) {
            DriveAction.None -> "nothing to do"
            DriveAction.BeginDrive -> "a drive has begun"
            is DriveAction.ScheduleParkCheck ->
                "park check in ${Duration.between(at, action.at).seconds}s"
            DriveAction.CancelParkCheck -> "the drive resumed, park check cancelled"
            is DriveAction.CapturePark ->
                "capturing after ${action.droveFor.seconds}s" +
                    if (action.confirmedOnFoot) ", confirmed on foot" else ""

            DriveAction.DiscardShortTrip ->
                if (drove != null && drove < machine.minimumTrip) {
                    "discarded: ${drove.seconds}s is under the ${machine.minimumTrip.seconds}s minimum trip"
                } else {
                    "discarded: a drive that never ended, abandoned after ${drove?.toHours()}h"
                }
        }
    }

    private suspend fun apply(action: DriveAction) {
        when (action) {
            DriveAction.None -> Unit

            DriveAction.BeginDrive -> {
                breadcrumb.start()
                // Nothing held from a previous stop may survive into this drive.
                settings.clearParkSnapshot()
                // Where the drive started is how the app tells two cars apart when no stereo did:
                // the car you are pulling away in was parked here a moment ago. It costs nothing —
                // this is the position the phone already had, not a fix taken to answer it.
                settings.setDriveOrigin(fixer.lastKnown()?.let(::originOf))
            }

            is DriveAction.ScheduleParkCheck -> {
                scheduleParkCheck(action.at)
                // Take the position now, while the car is still where it was left. The debounce
                // that follows is there to decide whether this was really a park; it is not
                // supposed to have an opinion about where, and before this it did — by the time
                // it expired the user had walked a street's length and the fix followed them.
                val snapshot = fixer.snapshot()?.let(::originOf)
                if (snapshot != null) {
                    settings.writeParkSnapshot(snapshot)
                    CurbsideLog.d("held a ±${snapshot.accuracyMeters.toInt()}m position for the park check")
                } else {
                    settings.clearParkSnapshot()
                    CurbsideLog.d("no cached position to hold; the capture will take its own")
                }
            }

            DriveAction.CancelParkCheck -> {
                cancelParkCheck()
                settings.clearParkSnapshot()
            }

            DriveAction.DiscardShortTrip -> {
                cancelParkCheck()
                breadcrumb.stop()
                settings.clearParkSnapshot()
                forgetDrive()
            }

            is DriveAction.CapturePark -> {
                cancelParkCheck()
                breadcrumb.stop()
                CurbsideLog.i("enqueuing the parking capture")
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
                    ParkingCaptureWorker.KEY_ENDED_AT to action.at.toEpochMilli(),
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

    /**
     * Clears what was remembered about the drive that just ended.
     *
     * Not called on the capture path: the worker runs after this and needs both the stereo and the
     * origin to name the car. It clears them itself once it has.
     */
    private suspend fun forgetDrive() {
        settings.setDriveStereo(null)
        settings.setDriveOrigin(null)
    }

    private fun originOf(fix: Fix) = Breadcrumb(
        latitude = fix.point.lat,
        longitude = fix.point.lon,
        accuracyMeters = fix.accuracyMeters,
        at = fix.at,
    )

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
