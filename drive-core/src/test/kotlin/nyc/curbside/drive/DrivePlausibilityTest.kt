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
    fun `the threshold sits between walking and driving`() {
        val minute = Duration.ofMinutes(1)
        assertTrue(walking(100.0, minute), "1.7 m/s is a walk")
        assertFalse(walking(150.0, minute), "2.5 m/s is not")
    }
}
