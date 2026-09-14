package nyc.curbside.drive

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Real devices, read off a real phone on 2026-09-14 with `dumpsys bluetooth_manager`.
 *
 * Three cars and three things that are not cars, which is the whole problem in miniature: two of
 * the cars declare themselves properly and one does not, and both headsets advertise hands-free
 * just like the car that does not. Any change to the heuristic has to keep answering these six
 * correctly before it is worth trusting on a seventh.
 */
class CarStereoLikelihoodTest {

    private fun likely(deviceClass: Int, vararg services: String) =
        CarStereoLikelihood.of(deviceClass, services.toList())

    /** Class of device 0x360420, a GMC head unit. */
    @Test
    fun `a head unit that declares itself as car audio is a car`() {
        assertEquals(
            CarLikelihood.CAR,
            likely(
                CarStereoLikelihood.AUDIO_VIDEO_CAR_AUDIO,
                "00001105-0000-1000-8000-00805f9b34fb",
                "0000110b-0000-1000-8000-00805f9b34fb",
                "0000111e-0000-1000-8000-00805f9b34fb",
                "00001133-0000-1000-8000-00805f9b34fb",
            ),
        )
    }

    /** 0x740420, "My VW 3959". A different manufacturer, the same honest declaration. */
    @Test
    fun `a second car audio device is a car too`() {
        assertEquals(
            CarLikelihood.CAR,
            likely(
                CarStereoLikelihood.AUDIO_VIDEO_CAR_AUDIO,
                "00001101-0000-1000-8000-00805f9b34fb",
                "00001133-0000-1000-8000-00805f9b34fb",
            ),
        )
    }

    /**
     * 0x360408, "myBuick". Reports hands-free rather than car audio — the case that made the first
     * version of this miss a car sitting in the user's own driveway.
     */
    @Test
    fun `a hands-free head unit is a car when it wants your messages`() {
        assertEquals(
            CarLikelihood.CAR,
            likely(
                CarStereoLikelihood.AUDIO_VIDEO_HANDSFREE,
                "0000110b-0000-1000-8000-00805f9b34fb",
                "0000110e-0000-1000-8000-00805f9b34fb",
                "0000111e-0000-1000-8000-00805f9b34fb",
                "00001133-0000-1000-8000-00805f9b34fb",
            ),
        )
    }

    /** 0x240404, Pixel Buds Pro. Advertises hands-free, and is emphatically not a car. */
    @Test
    fun `earbuds are not a car`() {
        assertEquals(
            CarLikelihood.OTHER,
            likely(
                CarStereoLikelihood.AUDIO_VIDEO_WEARABLE_HEADSET,
                "0000110b-0000-1000-8000-00805f9b34fb",
                "0000110e-0000-1000-8000-00805f9b34fb",
                "0000111e-0000-1000-8000-00805f9b34fb",
                "00001124-0000-1000-8000-00805f9b34fb",
            ),
        )
    }

    /** 0x240418, bone conduction headphones. */
    @Test
    fun `headphones are not a car`() {
        assertEquals(
            CarLikelihood.OTHER,
            likely(
                CarStereoLikelihood.AUDIO_VIDEO_HEADPHONES,
                "00001108-0000-1000-8000-00805f9b34fb",
                "0000110b-0000-1000-8000-00805f9b34fb",
                "0000111e-0000-1000-8000-00805f9b34fb",
            ),
        )
    }

    /** 0x000704, a running watch. Not even audio. */
    @Test
    fun `a watch is not a car`() {
        assertEquals(CarLikelihood.OTHER, likely(0x0704))
    }

    @Test
    fun `a speakerphone stays a maybe, and is never added on its own`() {
        // Hands-free with nothing a dashboard would want. Offered in the list, never assumed.
        assertEquals(
            CarLikelihood.MAYBE,
            likely(
                CarStereoLikelihood.AUDIO_VIDEO_HANDSFREE,
                "0000111e-0000-1000-8000-00805f9b34fb",
            ),
        )
    }

    @Test
    fun `an unknown device says nothing either way`() {
        assertEquals(CarLikelihood.OTHER, CarStereoLikelihood.of(null, emptyList()))
    }
}
