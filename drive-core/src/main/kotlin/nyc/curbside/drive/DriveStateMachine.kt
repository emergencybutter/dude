package nyc.curbside.drive

import java.time.Duration
import java.time.Instant

enum class DrivePhase {
    /** Not in a car as far as we know. Nothing is running, nothing is being sampled. */
    IDLE,

    /** A drive is under way. The passive location breadcrumb is subscribed. */
    DRIVING,

    /** A drive-ended signal arrived and we are waiting out its debounce before believing it. */
    CONFIRMING_PARK,
}

/**
 * Everything the detector needs to remember between broadcasts. Small and serialisable on purpose:
 * it is persisted to DataStore, because the process is expected to be killed between the signal
 * that starts a drive and the signal that ends it.
 */
data class DriveState(
    val phase: DrivePhase = DrivePhase.IDLE,
    val driveStartedAt: Instant? = null,
    val startedBy: SignalSource? = null,
    /** When the pending park confirmation is due to fire, if [phase] is [DrivePhase.CONFIRMING_PARK]. */
    val confirmAt: Instant? = null,
    val endedBy: SignalSource? = null,
)

/** What the Android layer should do about a state change. */
sealed interface DriveAction {
    /** Nothing to do; the signal was redundant or arrived in a phase that does not care. */
    data object None : DriveAction

    /**
     * A drive has started. Subscribe the passive location listener so a breadcrumb exists even if
     * the parking fix later fails in a garage.
     */
    data object BeginDrive : DriveAction

    /** Set a wake-up alarm for [at] to re-enter the machine via [DriveStateMachine.onTimer]. */
    data class ScheduleParkCheck(val at: Instant) : DriveAction

    /** The pending alarm is moot; cancel it. */
    data object CancelParkCheck : DriveAction

    /** Take the parking fix now and record the event. */
    data class CapturePark(
        val endedBy: SignalSource,
        val droveFor: Duration,
        /** True when the machine skipped the debounce because the user was seen walking. */
        val confirmedOnFoot: Boolean,
    ) : DriveAction

    /**
     * The "drive" was too short to have been a drive — sitting in the car to fetch something, or a
     * stereo that paired while the car stayed parked. Drop it and keep the previous parking spot.
     */
    data object DiscardShortTrip : DriveAction
}

data class DriveTransition(val state: DriveState, val action: DriveAction)

/**
 * Fuses the three detection signals into one parking decision.
 *
 * The whole point of this class is that it is pure: no Android, no clock, no I/O. The Android layer
 * feeds it signals and a timestamp and does what it is told. That makes the interesting
 * cases — a red light mid-drive, a Bluetooth dropout, sitting in the parked car with the radio
 * on — testable in milliseconds instead of by driving around Brooklyn.
 */
class DriveStateMachine(
    /**
     * A "drive" shorter than this never produces a parking pin. Covers sitting in the car with the
     * stereo on, and moving the car ten feet.
     */
    private val minimumTrip: Duration = Duration.ofSeconds(90),
    /**
     * If we somehow never see the end of a drive, give up rather than believing the car is in
     * motion forever and holding a passive location subscription open.
     */
    private val maximumTrip: Duration = Duration.ofHours(12),
) {

    fun onSignal(state: DriveState, signal: DriveSignal): DriveTransition = when (signal.kind) {
        SignalKind.DRIVE_STARTED -> onDriveStarted(state, signal)
        SignalKind.DRIVE_ENDED -> onDriveEnded(state, signal)
        SignalKind.WALKING_STARTED -> onWalking(state, signal)
    }

    /**
     * Called when the alarm scheduled by [DriveAction.ScheduleParkCheck] fires. Also safe to call
     * opportunistically (say, on app launch) to flush a confirmation whose alarm was dropped by the
     * system — hence the explicit "is it actually due" check rather than trusting the caller.
     */
    fun onTimer(state: DriveState, now: Instant): DriveTransition {
        if (state.phase != DrivePhase.CONFIRMING_PARK) return DriveTransition(state, DriveAction.None)
        val due = state.confirmAt ?: return DriveTransition(state, DriveAction.None)
        if (now.isBefore(due)) return DriveTransition(state, DriveAction.None)

        return park(state, state.endedBy ?: SignalSource.ACTIVITY_RECOGNITION, now, onFoot = false)
    }

    private fun onDriveStarted(state: DriveState, signal: DriveSignal): DriveTransition = when (state.phase) {
        DrivePhase.IDLE -> DriveTransition(
            DriveState(
                phase = DrivePhase.DRIVING,
                driveStartedAt = signal.at,
                startedBy = signal.source,
            ),
            DriveAction.BeginDrive,
        )

        // The drive never actually ended: a red light, a cable reseated, a stereo that reconnected.
        // Cancel the pending confirmation and carry on with the original start time.
        DrivePhase.CONFIRMING_PARK -> DriveTransition(
            state.copy(phase = DrivePhase.DRIVING, confirmAt = null, endedBy = null),
            DriveAction.CancelParkCheck,
        )

        // Already driving. A second source agreeing is not news.
        DrivePhase.DRIVING -> DriveTransition(state, DriveAction.None)
    }

    private fun onDriveEnded(state: DriveState, signal: DriveSignal): DriveTransition = when (state.phase) {
        DrivePhase.IDLE -> DriveTransition(state, DriveAction.None)

        // Already counting down. The first source to report the end owns the deadline; letting a
        // second source push it back would let a flapping Bluetooth link defer parking forever.
        DrivePhase.CONFIRMING_PARK -> DriveTransition(state, DriveAction.None)

        DrivePhase.DRIVING -> {
            val started = state.driveStartedAt
            val elapsed = if (started != null) Duration.between(started, signal.at) else Duration.ZERO

            when {
                started != null && elapsed < minimumTrip ->
                    DriveTransition(DriveState(), DriveAction.DiscardShortTrip)

                signal.endDebounce.isZero ->
                    park(state, signal.source, signal.at, onFoot = false)

                else -> {
                    val at = signal.at.plus(signal.endDebounce)
                    DriveTransition(
                        state.copy(
                            phase = DrivePhase.CONFIRMING_PARK,
                            confirmAt = at,
                            endedBy = signal.source,
                        ),
                        DriveAction.ScheduleParkCheck(at),
                    )
                }
            }
        }
    }

    /**
     * Walking beats every debounce. If the user is on foot, the car is not moving, whatever the
     * stereo thinks — so a pending confirmation fires now and a drive we never saw end is closed out.
     */
    private fun onWalking(state: DriveState, signal: DriveSignal): DriveTransition = when (state.phase) {
        DrivePhase.IDLE -> DriveTransition(state, DriveAction.None)

        DrivePhase.CONFIRMING_PARK ->
            park(state, state.endedBy ?: signal.source, signal.at, onFoot = true)

        DrivePhase.DRIVING -> {
            val started = state.driveStartedAt
            val elapsed = if (started != null) Duration.between(started, signal.at) else Duration.ZERO
            if (started != null && elapsed < minimumTrip) {
                DriveTransition(DriveState(), DriveAction.DiscardShortTrip)
            } else {
                park(state, signal.source, signal.at, onFoot = true)
            }
        }
    }

    private fun park(
        state: DriveState,
        endedBy: SignalSource,
        at: Instant,
        onFoot: Boolean,
    ): DriveTransition {
        val droveFor = state.driveStartedAt?.let { Duration.between(it, at) } ?: Duration.ZERO
        return DriveTransition(
            DriveState(),
            DriveAction.CapturePark(endedBy = endedBy, droveFor = droveFor, confirmedOnFoot = onFoot),
        )
    }

    /**
     * Housekeeping for a drive that never ended — the phone died, the receiver was dropped, the
     * user got out without their phone. Called on app launch and from the daily maintenance worker.
     */
    fun onStaleCheck(state: DriveState, now: Instant): DriveTransition {
        val started = state.driveStartedAt ?: return DriveTransition(state, DriveAction.None)
        if (state.phase == DrivePhase.IDLE) return DriveTransition(state, DriveAction.None)
        if (Duration.between(started, now) < maximumTrip) return DriveTransition(state, DriveAction.None)

        // No parking pin: we have no idea where the car was left, and a wrong pin is worse than none.
        return DriveTransition(DriveState(), DriveAction.DiscardShortTrip)
    }
}
