package nyc.curbside.drive

import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DriveStateMachineTest {

    private val machine = DriveStateMachine()
    private val t0: Instant = Instant.parse("2026-09-14T18:00:00Z")

    private fun at(seconds: Long) = t0.plusSeconds(seconds)

    private fun signal(source: SignalSource, kind: SignalKind, seconds: Long) =
        DriveSignal(source, kind, at(seconds))

    /** Drives from IDLE to a state that has been driving for [seconds]. */
    private fun driving(source: SignalSource = SignalSource.ANDROID_AUTO): DriveState =
        machine.onSignal(DriveState(), signal(source, SignalKind.DRIVE_STARTED, 0)).state

    @Test
    fun `plugging into android auto starts a drive and the breadcrumb`() {
        val (state, action) = machine.onSignal(
            DriveState(),
            signal(SignalSource.ANDROID_AUTO, SignalKind.DRIVE_STARTED, 0),
        )

        assertEquals(DrivePhase.DRIVING, state.phase)
        assertEquals(t0, state.driveStartedAt)
        assertEquals(SignalSource.ANDROID_AUTO, state.startedBy)
        assertEquals(DriveAction.BeginDrive, action)
    }

    @Test
    fun `a second source agreeing mid-drive changes nothing`() {
        val start = driving()
        val (state, action) = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_STARTED, 30),
        )

        assertEquals(DriveAction.None, action)
        assertEquals(t0, state.driveStartedAt) // still the original start, not the later one
    }

    @Test
    fun `unplugging schedules a confirmation rather than parking immediately`() {
        val start = driving()
        val (state, action) = machine.onSignal(
            start,
            signal(SignalSource.ANDROID_AUTO, SignalKind.DRIVE_ENDED, 600),
        )

        assertEquals(DrivePhase.CONFIRMING_PARK, state.phase)
        val scheduled = assertIs<DriveAction.ScheduleParkCheck>(action)
        assertEquals(at(620), scheduled.at) // 20s debounce for a head-unit detach
        assertEquals(at(620), state.confirmAt)
    }

    @Test
    fun `bluetooth gets a longer debounce than android auto`() {
        val start = driving(SignalSource.CAR_BLUETOOTH)
        val (_, action) = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 600),
        )

        assertEquals(at(645), assertIs<DriveAction.ScheduleParkCheck>(action).at)
    }

    @Test
    fun `a bluetooth dropout that reconnects cancels the confirmation`() {
        val start = driving(SignalSource.CAR_BLUETOOTH)
        val dropped = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 600),
        ).state

        val (state, action) = machine.onSignal(
            dropped,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_STARTED, 612),
        )

        assertEquals(DriveAction.CancelParkCheck, action)
        assertEquals(DrivePhase.DRIVING, state.phase)
        assertNull(state.confirmAt)
        assertEquals(t0, state.driveStartedAt) // the drive was continuous, not restarted
    }

    @Test
    fun `the confirmation alarm parks the car`() {
        val start = driving()
        val pending = machine.onSignal(
            start,
            signal(SignalSource.ANDROID_AUTO, SignalKind.DRIVE_ENDED, 600),
        ).state

        val (state, action) = machine.onTimer(pending, at(620))

        val capture = assertIs<DriveAction.CapturePark>(action)
        assertEquals(SignalSource.ANDROID_AUTO, capture.endedBy)
        assertEquals(Duration.ofSeconds(620), capture.droveFor)
        assertTrue(!capture.confirmedOnFoot)
        assertEquals(DrivePhase.IDLE, state.phase)
    }

    @Test
    fun `the pin is dated from when the car stopped, not from when the debounce expired`() {
        val start = driving()
        val pending = machine.onSignal(
            start,
            signal(SignalSource.ANDROID_AUTO, SignalKind.DRIVE_ENDED, 600),
        ).state

        val (_, action) = machine.onTimer(pending, at(620))

        // The twenty seconds between unplugging and the alarm are twenty seconds of walking away
        // from the car. Dating the capture from the alarm is how a pin ends up on the street the
        // user left by rather than the one the car is on.
        val capture = assertIs<DriveAction.CapturePark>(action)
        assertEquals(at(600), capture.at)
        // How long the drive lasted is still measured to the moment we stopped watching.
        assertEquals(Duration.ofSeconds(620), capture.droveFor)
    }

    @Test
    fun `the walking short-circuit is dated from the end signal, not from the walking`() {
        val start = driving(SignalSource.CAR_BLUETOOTH)
        val pending = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 600),
        ).state

        val (_, action) = machine.onSignal(
            pending,
            signal(SignalSource.ACTIVITY_RECOGNITION, SignalKind.WALKING_STARTED, 630),
        )

        // Being seen walking is the strongest possible evidence that the phone is no longer with
        // the car, so it is the worst possible moment to ask where the phone is.
        val capture = assertIs<DriveAction.CapturePark>(action)
        assertEquals(at(600), capture.at)
    }

    @Test
    fun `the stop time survives the process dying under the debounce`() {
        val start = driving()
        val pending = machine.onSignal(
            start,
            signal(SignalSource.ANDROID_AUTO, SignalKind.DRIVE_ENDED, 600),
        ).state

        // What DataStore would hand back after the process was killed and the alarm woke it again.
        val revived = DriveState(
            phase = pending.phase,
            driveStartedAt = pending.driveStartedAt,
            startedBy = pending.startedBy,
            confirmAt = pending.confirmAt,
            endedBy = pending.endedBy,
            endedAt = pending.endedAt,
        )

        val capture = assertIs<DriveAction.CapturePark>(machine.onTimer(revived, at(620)).action)
        assertEquals(at(600), capture.at)
    }

    @Test
    fun `a walk with no end signal behind it is dated from the walk itself`() {
        val start = driving()

        val (_, action) = machine.onSignal(
            start,
            signal(SignalSource.ACTIVITY_RECOGNITION, SignalKind.WALKING_STARTED, 900),
        )

        // Nothing better exists here: no source ever said the drive ended, so the moment the user
        // was seen on foot is the earliest evidence the car had stopped.
        assertEquals(at(900), assertIs<DriveAction.CapturePark>(action).at)
    }

    @Test
    fun `an alarm that fires early is ignored`() {
        val start = driving()
        val pending = machine.onSignal(
            start,
            signal(SignalSource.ANDROID_AUTO, SignalKind.DRIVE_ENDED, 600),
        ).state

        val (state, action) = machine.onTimer(pending, at(610))

        assertEquals(DriveAction.None, action)
        assertEquals(DrivePhase.CONFIRMING_PARK, state.phase)
    }

    @Test
    fun `walking short-circuits the debounce`() {
        val start = driving(SignalSource.CAR_BLUETOOTH)
        val pending = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 600),
        ).state

        val (state, action) = machine.onSignal(
            pending,
            signal(SignalSource.ACTIVITY_RECOGNITION, SignalKind.WALKING_STARTED, 615),
        )

        val capture = assertIs<DriveAction.CapturePark>(action)
        assertTrue(capture.confirmedOnFoot)
        // Credit stays with the source that actually saw the drive end.
        assertEquals(SignalSource.CAR_BLUETOOTH, capture.endedBy)
        assertEquals(DrivePhase.IDLE, state.phase)
    }

    @Test
    fun `walking during a drive we never saw end still parks the car`() {
        val start = driving()
        val (_, action) = machine.onSignal(
            start,
            signal(SignalSource.ACTIVITY_RECOGNITION, SignalKind.WALKING_STARTED, 900),
        )

        assertIs<DriveAction.CapturePark>(action)
    }

    @Test
    fun `an activity transition needs no debounce because the detector already applied one`() {
        val start = driving(SignalSource.ACTIVITY_RECOGNITION)
        val (state, action) = machine.onSignal(
            start,
            signal(SignalSource.ACTIVITY_RECOGNITION, SignalKind.DRIVE_ENDED, 600),
        )

        assertIs<DriveAction.CapturePark>(action)
        assertEquals(DrivePhase.IDLE, state.phase)
    }

    @Test
    fun `sitting in the car with the stereo on never drops a pin`() {
        val start = driving(SignalSource.CAR_BLUETOOTH)
        val (state, action) = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 40),
        )

        assertEquals(DriveAction.DiscardShortTrip, action)
        assertEquals(DriveState(), state)
    }

    @Test
    fun `a drive-ended signal while idle is ignored`() {
        val (state, action) = machine.onSignal(
            DriveState(),
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 0),
        )

        assertEquals(DriveAction.None, action)
        assertEquals(DrivePhase.IDLE, state.phase)
    }

    @Test
    fun `a flapping stereo cannot defer parking indefinitely`() {
        val start = driving(SignalSource.CAR_BLUETOOTH)
        val first = machine.onSignal(
            start,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 600),
        ).state

        // A second end signal arrives before the deadline; it must not push the deadline out.
        val (second, action) = machine.onSignal(
            first,
            signal(SignalSource.CAR_BLUETOOTH, SignalKind.DRIVE_ENDED, 630),
        )

        assertEquals(DriveAction.None, action)
        assertEquals(at(645), second.confirmAt)
    }

    @Test
    fun `a manual park request is believed immediately`() {
        val start = driving()
        val (_, action) = machine.onSignal(
            start,
            signal(SignalSource.MANUAL, SignalKind.DRIVE_ENDED, 600),
        )

        assertEquals(SignalSource.MANUAL, assertIs<DriveAction.CapturePark>(action).endedBy)
    }

    @Test
    fun `a drive that never ends is abandoned rather than left running`() {
        val start = driving()
        val (state, action) = machine.onStaleCheck(start, at(13 * 3600))

        assertEquals(DriveAction.DiscardShortTrip, action)
        assertEquals(DrivePhase.IDLE, state.phase)
    }

    @Test
    fun `a long but plausible drive is not abandoned`() {
        val start = driving()
        val (state, action) = machine.onStaleCheck(start, at(4 * 3600))

        assertEquals(DriveAction.None, action)
        assertEquals(DrivePhase.DRIVING, state.phase)
    }
}
