package nyc.curbside.drive

import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DrivePlausibilityTest {

    private fun walking(
        distance: Double?,
        duration: Duration?,
        endedBy: SignalSource = SignalSource.ACTIVITY_RECOGNITION,
    ) = DrivePlausibility.looksLikeWalking(distance, duration, endedBy)

    @Test
    fun `the Prospect Park case is recognised as a walk`() {
        // 2026-09-14: a parking at 08:49:57, then a second 369m away at 08:54:17. The car never
        // moved; its owner walked away from it, and the app moved the pin to follow them.
        assertTrue(walking(369.0, Duration.ofSeconds(260)))
    }

    @Test
    fun `a real short drive is left alone`() {
        // Four hundred metres in ninety seconds is a drive around the block, not a walk.
        assertFalse(walking(400.0, Duration.ofSeconds(90)))
    }

    @Test
    fun `a car stereo is never second-guessed`() {
        // The stereo unpairing means there was a car. Slow is then traffic, not walking.
        assertFalse(walking(369.0, Duration.ofSeconds(260), SignalSource.CAR_BLUETOOTH))
        assertFalse(walking(369.0, Duration.ofSeconds(260), SignalSource.ANDROID_AUTO))
    }

    @Test
    fun `a long crawl is a gridlocked drive, not a walk`() {
        // Five kilometres at walking pace is an hour in traffic. Nobody walks that and parks.
        assertFalse(walking(5_000.0, Duration.ofHours(1)))
        assertFalse(walking(800.0, Duration.ofMinutes(20)))
    }

    @Test
    fun `no starting position means nothing to check`() {
        // The origin is whatever fix the phone already had, and often there is none. An absent
        // check must let the parking through, never discard it.
        assertFalse(walking(null, Duration.ofSeconds(260)))
        assertFalse(walking(369.0, null))
    }

    @Test
    fun `a drive that ends where it started is not judged on speed`() {
        // Zero distance over a real duration is slower than walking, but this is the circling case
        // — round the block and back to the same spot — and the duration guard already covers the
        // ones that matter.
        assertTrue(walking(0.0, Duration.ofSeconds(600)))
    }

    @Test
    fun `half a kilometre is far enough to be believed, however slow`() {
        // A real drive on 2026-09-14 was detected, ran, and had its parking discarded here. Beyond
        // this distance the benefit of the doubt goes to the driver: a wrongly kept pin is a pin
        // the user can correct, a wrongly discarded one leaves them looking at last week's street.
        assertFalse(walking(600.0, Duration.ofMinutes(9)))
        assertTrue(walking(400.0, Duration.ofMinutes(9)))
    }

    @Test
    fun `a real drive measured against a stale origin is not a walk`() {
        // The origin is whatever fix the phone had cached when the drive began, and it can be a
        // quarter of an hour old. Measuring the trip from that timestamp stretched a four-minute
        // drive into a twenty-minute one and silently discarded the parking. The duration has to
        // come from the state machine, which knows when the drive actually started.
        assertFalse(walking(450.0, Duration.ofMinutes(2)), "450m in two minutes is driving")
        assertTrue(
            walking(450.0, Duration.ofMinutes(15)),
            "the same distance over a stale fifteen-minute window is what used to be passed in",
        )
    }

    @Test
    fun `the threshold sits between walking and driving`() {
        val minute = Duration.ofMinutes(1)
        assertTrue(walking(100.0, minute), "1.7 m/s is a walk")
        assertFalse(walking(150.0, minute), "2.5 m/s is not")
    }
}
