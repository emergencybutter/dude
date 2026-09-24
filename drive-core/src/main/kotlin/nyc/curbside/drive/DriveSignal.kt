package nyc.curbside.drive

import java.time.Duration
import java.time.Instant

/**
 * Where a piece of evidence about driving came from.
 *
 * Sources differ in three ways that the state machine cares about: how much they cost, how much
 * they can be trusted, and whether they can wake a dead process.
 */
enum class SignalSource {
    /**
     * `androidx.car.app.connection.CarConnection` reports the head unit attaching or detaching.
     * Free and unambiguous, but only observable while our process is alive, so it refines a drive
     * rather than starting one.
     */
    ANDROID_AUTO,

    /**
     * Play Services activity transitions for `IN_VEHICLE` and `ON_FOOT`. Delivered by PendingIntent
     * to a manifest receiver, so it works with the app killed. Runs on the sensor hub, and the
     * detector debounces internally — a transition is already a considered opinion, not a sample.
     */
    ACTIVITY_RECOGNITION,

    /**
     * ACL connect/disconnect for the car stereo the user nominated. Free, wakes a dead process, and
     * for most cars tracks the ignition exactly. Prone to brief dropouts, hence the longest debounce.
     */
    CAR_BLUETOOTH,

    /** The user pressed a button. Always believed immediately. */
    MANUAL,
    ;

    /**
     * A physical connection to the car, as against an inference about motion. While one is up the
     * car is on and the phone is in it, whatever the accelerometer makes of the traffic.
     */
    val isCarLink: Boolean
        get() = this == ANDROID_AUTO || this == CAR_BLUETOOTH
}

enum class SignalKind {
    /** Evidence a drive has begun: head unit attached, vehicle transition entered, stereo paired. */
    DRIVE_STARTED,

    /** Evidence a drive has ended. Debounced per source before it is acted on. */
    DRIVE_ENDED,

    /**
     * The user is walking. Arriving during or just after a drive, this is the strongest possible
     * confirmation that the car has been left behind, and it short-circuits the debounce.
     */
    WALKING_STARTED,
}

data class DriveSignal(
    val source: SignalSource,
    val kind: SignalKind,
    val at: Instant,
) {
    /**
     * How long to wait before believing a [SignalKind.DRIVE_ENDED] from this source.
     *
     * Unplugging from Android Auto is nearly always real, but people do reseat a cable. Bluetooth
     * drops out at random. Activity recognition gets the most patience of all: it leaves
     * `IN_VEHICLE` in any jam long enough to look like standing still, and believing it on the spot
     * dropped pins in the middle of the avenue. Walking away confirms a real stop at once, so the
     * wait only costs anything when the user stays in the car — and the position is held from the
     * signal, not the alarm, so it never moves the pin.
     */
    val endDebounce: Duration
        get() = when (source) {
            SignalSource.ACTIVITY_RECOGNITION -> Duration.ofMinutes(3)
            SignalSource.MANUAL -> Duration.ZERO
            SignalSource.ANDROID_AUTO -> Duration.ofSeconds(20)
            SignalSource.CAR_BLUETOOTH -> Duration.ofSeconds(45)
        }
}
