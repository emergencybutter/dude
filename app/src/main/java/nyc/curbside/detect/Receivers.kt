package nyc.curbside.detect

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nyc.curbside.CurbsideLog
import nyc.curbside.asp.NYC
import nyc.curbside.di.ApplicationScope
import nyc.curbside.drive.DriveSignal
import nyc.curbside.drive.SignalKind
import nyc.curbside.drive.SignalSource
import nyc.curbside.notify.MoveReminderScheduler

/**
 * Base for the detection receivers.
 *
 * All of them do the same dance: take the pending-result token so the process is not killed the
 * instant `onReceive` returns, hand the work to a coroutine, and finish. None of them do more than
 * a couple of DataStore reads and an alarm call, so this stays well inside the ten seconds a
 * receiver is allowed.
 */
abstract class CoroutineBroadcastReceiver : BroadcastReceiver() {

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    protected fun goAsyncIn(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Target of the activity-transition PendingIntent. Fires with the app dead, which is the whole
 * point: this is what lets detection cost nothing between drives.
 */
@AndroidEntryPoint
class ActivityTransitionReceiver : CoroutineBroadcastReceiver() {

    @Inject lateinit var coordinator: DriveCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        // No super.onReceive: BroadcastReceiver.onReceive is abstract, and Hilt's Gradle plugin
        // rewrites this method to call its generated inject(context) before the first statement.
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        // A single delivery can carry several transitions; they arrive oldest first and the state
        // machine is order-sensitive, so replay them in order rather than taking only the last.
        //
        // Each is dated from when it happened, not when it arrived. Deliveries are batched and can
        // lag by minutes; stamping them "now" made the walk to the car look like it came after the
        // stereo connected, and the machine threw the drive away as a ninety-second trip.
        val nowWall = Instant.now()
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val signals = result.transitionEvents.mapNotNull { event ->
            val kind = when {
                event.activityType == DetectedActivity.IN_VEHICLE &&
                    event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER ->
                    SignalKind.DRIVE_STARTED

                event.activityType == DetectedActivity.IN_VEHICLE &&
                    event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT ->
                    SignalKind.DRIVE_ENDED

                (event.activityType == DetectedActivity.ON_FOOT || event.activityType == DetectedActivity.WALKING) &&
                    event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER ->
                    SignalKind.WALKING_STARTED

                else -> null
            }
            val age = (nowElapsed - event.elapsedRealTimeNanos).coerceAtLeast(0L)
            kind?.let { DriveSignal(SignalSource.ACTIVITY_RECOGNITION, it, nowWall.minusNanos(age)) }
        }

        if (signals.isEmpty()) {
            CurbsideLog.d("activity transitions arrived but none were ones we watch for")
            return
        }
        CurbsideLog.i("activity recognition: ${signals.joinToString { it.kind.name }}")
        goAsyncIn { signals.forEach { coordinator.onSignal(it) } }
    }
}

/**
 * A car stereo pairing or dropping.
 *
 * Only devices nominated in Settings count. Without that filter every pair of headphones in the
 * city would look like a car, and a walk to the subway with earbuds in would drop a parking pin on
 * the pavement.
 *
 * Which stereo it was is recorded, not just that it was one of ours: with two cars in a household
 * the address is the only thing that says whose drive this is.
 */
@AndroidEntryPoint
class CarBluetoothReceiver : CoroutineBroadcastReceiver() {

    @Inject lateinit var coordinator: DriveCoordinator

    @Inject lateinit var settings: nyc.curbside.data.CurbsideSettings

    override fun onReceive(context: Context, intent: Intent) {
        val kind = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> SignalKind.DRIVE_STARTED
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> SignalKind.DRIVE_ENDED
            else -> return
        }

        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address ?: return

        val edge = if (kind == SignalKind.DRIVE_STARTED) "connected" else "disconnected"

        goAsyncIn {
            val vehicle = settings.readVehicles().firstOrNull { it.bluetoothAddress == address }
            if (vehicle == null) {
                // Far and away the commonest reason a real drive goes unnoticed: the car's stereo
                // was never nominated in Settings, so every edge it produces is thrown away here.
                CurbsideLog.i("a bluetooth device $edge, but it is not one of your cars — ignored")
                CurbsideLog.d("  the device was $address; nominate it in Settings if it is a car")
                return@goAsyncIn
            }
            CurbsideLog.i("${vehicle.name} $edge")
            if (kind == SignalKind.DRIVE_STARTED) settings.setDriveStereo(vehicle.bluetoothAddress)
            coordinator.onSignal(DriveSignal(SignalSource.CAR_BLUETOOTH, kind, Instant.now()))
        }
    }
}

/** The park-confirmation debounce expiring. */
@AndroidEntryPoint
class ParkCheckAlarmReceiver : CoroutineBroadcastReceiver() {

    @Inject lateinit var coordinator: DriveCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        goAsyncIn { coordinator.onParkCheckDue() }
    }
}

/**
 * Alarms and transition registrations do not survive a reboot or an app update, so re-arm both.
 * Also reconciles a drive that was in flight when the phone went down.
 */
@AndroidEntryPoint
class BootReceiver : CoroutineBroadcastReceiver() {

    @Inject lateinit var registrar: DetectionRegistrar

    @Inject lateinit var coordinator: DriveCoordinator

    @Inject lateinit var dao: nyc.curbside.data.db.ParkingEventDao

    @Inject lateinit var reminders: MoveReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        goAsyncIn {
            registrar.ensureRegistered()
            coordinator.reconcile()

            // The move reminders went down with everything else. That used to be close to
            // harmless, because the only alarm outstanding was an hour long at most; a warning
            // armed a day ahead is long enough that a reboot under it is ordinary, and losing it
            // silently is exactly the failure the day's notice exists to prevent.
            val active = dao.active()
            active.forEach { event ->
                val moveBy = event.moveByEpochMillis ?: return@forEach
                reminders.schedule(event.id, Instant.ofEpochMilli(moveBy).atZone(NYC))
            }
            CurbsideLog.i(
                "restarted: detection re-registered, reminders re-armed for " +
                    "${active.count { it.moveByEpochMillis != null }} of ${active.size} parked cars",
            )
        }
    }
}
