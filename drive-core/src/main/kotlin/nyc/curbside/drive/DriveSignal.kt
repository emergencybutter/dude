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
     * Activity recognition has already done this work internally. Unplugging from Android Auto is
     * nearly always real, but people do reseat a cable. Bluetooth drops out at random and gets the
     * most patience — the cost of waiting is a slightly later notification, while the cost of not
     * waiting is a parking pin dropped at a traffic light.
     */
    val endDebounce: Duration
        get() = when (source) {
            SignalSource.ACTIVITY_RECOGNITION -> Duration.ZERO
            SignalSource.MANUAL -> Duration.ZERO
            SignalSource.ANDROID_AUTO -> Duration.ofSeconds(20)
            SignalSource.CAR_BLUETOOTH -> Duration.ofSeconds(45)
        }
}
