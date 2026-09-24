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
    /**
     * When the end signal that opened this confirmation was timestamped.
     *
     * Kept separately from [confirmAt] because the debounce answers "did the car park", and the
     * capture separately needs "when" — and the honest answer to the second is the moment the
     * signal arrived, not the moment the alarm got round to firing.
     */
    val endedAt: Instant? = null,
    /**
     * The car links — stereo, head unit — that are connected right now, and since when.
     *
     * Not part of any one drive, so it survives every reset back to [DrivePhase.IDLE]: a stereo
     * that connected before a short trip was discarded is still connected afterwards, and forgetting
     * that is how a drive came to be judged by activity recognition alone while the car was plainly
     * on. As long as a link is up, the car is running and nothing on foot is believed.
     */
    val carLinks: Map<SignalSource, Instant> = emptyMap(),
)

/** What the Android layer should do about a state change. */
sealed interface DriveAction {
    /** Nothing to do; the signal was redundant or arrived in a phase that does not care. */
    data object None : DriveAction

    /**
     * Nothing to do, for a reason worth logging: a signal that would have ended the drive was
     * overruled. These are the decisions that explain a parking that did *not* happen.
     */
    data class Ignore(val reason: String) : DriveAction

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
        /**
         * When the car actually stopped — the moment the end signal was timestamped, not the
         * moment this action was produced.
         *
         * The two differ by the source's debounce, and the gap is the distance the user has walked
         * since. Anything judging how stale a position is has to measure against this, or it will
         * prefer a fresh fix of the pavement to a slightly older one of the car.
         */
        val at: Instant,
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
    val minimumTrip: Duration = Duration.ofSeconds(90),
    /**
     * If we somehow never see the end of a drive, give up rather than believing the car is in
     * motion forever and holding a passive location subscription open. Also how long a car link is
     * believed without news, in case its disconnect was never delivered.
     */
    val maximumTrip: Duration = Duration.ofHours(12),
) {

    fun onSignal(state: DriveState, signal: DriveSignal): DriveTransition {
        val linked = trackLinks(state, signal)
        return when (signal.kind) {
            SignalKind.DRIVE_STARTED -> onDriveStarted(linked, signal)
            SignalKind.DRIVE_ENDED -> onDriveEnded(linked, signal)
            SignalKind.WALKING_STARTED -> onWalking(linked, signal)
        }
    }

    /**
     * A car link turned out not to be connected, without an edge to say when it went: the process
     * was dead through the disconnect, and the first thing the head unit reported on waking was
     * "not connected". Nothing is known about where or when the drive ended, so no pin comes of it;
     * the link just stops vouching for the car, and the other sources get their say again.
     */
    fun onLinkAbsent(state: DriveState, source: SignalSource): DriveTransition =
        if (source !in state.carLinks) {
            DriveTransition(state, DriveAction.None)
        } else {
            DriveTransition(
                state.copy(carLinks = state.carLinks - source),
                DriveAction.Ignore("$source was found disconnected with no edge seen; it no longer vouches for the car"),
            )
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

        return park(
            state,
            state.endedBy ?: SignalSource.ACTIVITY_RECOGNITION,
            now,
            onFoot = false,
            stoppedAt = state.endedAt ?: now,
        )
    }

    /** Keeps [DriveState.carLinks] in step with what the stereo and head unit report, in any phase. */
    private fun trackLinks(state: DriveState, signal: DriveSignal): DriveState {
        if (!signal.source.isCarLink) return state
        return when (signal.kind) {
            SignalKind.DRIVE_STARTED -> state.copy(carLinks = state.carLinks + (signal.source to signal.at))
            SignalKind.DRIVE_ENDED -> state.copy(carLinks = state.carLinks - signal.source)
            SignalKind.WALKING_STARTED -> state
        }
    }

    /**
     * Why a signal saying the drive is over should not be believed, or null if it should.
     *
     * Two things overrule it. A car link still connected: a stereo or head unit only stays up while
     * the car is on and the phone is in it, so activity recognition calling a traffic jam "still" or
     * a pothole "walking" is simply wrong — and so is one link dropping while another holds. And a
     * signal dated before the drive began: activity transitions are delivered in batches, and the
     * walk *to* the car used to arrive after the stereo had connected and discard the drive as too
     * short, taking everything known about it with it.
     */
    private fun overruled(state: DriveState, signal: DriveSignal): String? {
        val started = state.driveStartedAt
        if (started != null && signal.at.isBefore(started)) {
            return "it predates the drive by ${Duration.between(signal.at, started).seconds}s"
        }
        val holding = state.carLinks.keys - signal.source
        if (signal.source != SignalSource.MANUAL && holding.isNotEmpty()) {
            return "${holding.joinToString()} still connected, so the car is still on"
        }
        return null
    }

    private fun onDriveStarted(state: DriveState, signal: DriveSignal): DriveTransition = when (state.phase) {
        DrivePhase.IDLE -> DriveTransition(
            state.copy(
                phase = DrivePhase.DRIVING,
                driveStartedAt = signal.at,
                startedBy = signal.source,
            ),
            DriveAction.BeginDrive,
        )

        // The drive never actually ended: a red light, a cable reseated, a stereo that reconnected.
        // Cancel the pending confirmation and carry on with the original start time.
        DrivePhase.CONFIRMING_PARK -> DriveTransition(
            state.copy(phase = DrivePhase.DRIVING, confirmAt = null, endedBy = null, endedAt = null),
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
            val overruled = overruled(state, signal)

            when {
                overruled != null -> DriveTransition(state, DriveAction.Ignore(overruled))

                started != null && elapsed < minimumTrip ->
                    DriveTransition(idle(state), DriveAction.DiscardShortTrip)

                signal.endDebounce.isZero ->
                    park(state, signal.source, signal.at, onFoot = false)

                else -> {
                    val at = signal.at.plus(signal.endDebounce)
                    DriveTransition(
                        state.copy(
                            phase = DrivePhase.CONFIRMING_PARK,
                            confirmAt = at,
                            endedBy = signal.source,
                            endedAt = signal.at,
                        ),
                        DriveAction.ScheduleParkCheck(at),
                    )
                }
            }
        }
    }

    /**
     * Walking beats every debounce. If the user is on foot, the car is not moving, whatever the
     * detector thought a moment ago — so a pending confirmation fires now and a drive we never saw
     * end is closed out. The one thing it does not beat is a car link that is still connected.
     */
    private fun onWalking(state: DriveState, signal: DriveSignal): DriveTransition {
        if (state.phase == DrivePhase.IDLE) return DriveTransition(state, DriveAction.None)
        overruled(state, signal)?.let { return DriveTransition(state, DriveAction.Ignore(it)) }

        return when (state.phase) {
            DrivePhase.IDLE -> DriveTransition(state, DriveAction.None)

            DrivePhase.CONFIRMING_PARK ->
                park(
                    state,
                    state.endedBy ?: signal.source,
                    signal.at,
                    onFoot = true,
                    stoppedAt = state.endedAt ?: signal.at,
                )

            DrivePhase.DRIVING -> {
                val started = state.driveStartedAt
                val elapsed = if (started != null) Duration.between(started, signal.at) else Duration.ZERO
                if (started != null && elapsed < minimumTrip) {
                    DriveTransition(idle(state), DriveAction.DiscardShortTrip)
                } else {
                    park(state, signal.source, signal.at, onFoot = true)
                }
            }
        }
    }

    private fun park(
        state: DriveState,
        endedBy: SignalSource,
        at: Instant,
        onFoot: Boolean,
        /**
         * When the car actually stopped, where that is known to be earlier than [at] — the end
         * signal's own timestamp, as against the moment the debounce or the walking shortcut got
         * round to acting on it. Only the recorded position cares about the difference; how long
         * the drive lasted is still measured to [at], since that is when we stopped watching.
         */
        stoppedAt: Instant = at,
    ): DriveTransition {
        val droveFor = state.driveStartedAt?.let { Duration.between(it, at) } ?: Duration.ZERO
        return DriveTransition(
            idle(state),
            DriveAction.CapturePark(
                endedBy = endedBy,
                droveFor = droveFor,
                confirmedOnFoot = onFoot,
                at = stoppedAt,
            ),
        )
    }

    /** Back to idle: the drive is forgotten, but not which car links are still up. */
    private fun idle(state: DriveState) = DriveState(carLinks = state.carLinks)

    /**
     * Housekeeping for a drive that never ended — the phone died, the receiver was dropped, the
     * user got out without their phone. Called on app launch and from the daily maintenance worker.
     *
     * Also lets go of a car link that has been "connected" for longer than any drive lasts: its
     * disconnect was lost, and a link nobody clears would overrule every end signal for good.
     */
    fun onStaleCheck(state: DriveState, now: Instant): DriveTransition {
        val freshLinks = state.carLinks.filterValues { Duration.between(it, now) < maximumTrip }
        val started = state.driveStartedAt
        val driveIsStale = state.phase != DrivePhase.IDLE &&
            started != null &&
            Duration.between(started, now) >= maximumTrip

        return when {
            // No parking pin: we have no idea where the car was left, and a wrong pin is worse than none.
            driveIsStale -> DriveTransition(DriveState(carLinks = freshLinks), DriveAction.DiscardShortTrip)

            freshLinks != state.carLinks -> DriveTransition(
                state.copy(carLinks = freshLinks),
                DriveAction.Ignore(
                    "${(state.carLinks.keys - freshLinks.keys).joinToString()} connected too long to believe; dropped",
                ),
            )

            else -> DriveTransition(state, DriveAction.None)
        }
    }
}
