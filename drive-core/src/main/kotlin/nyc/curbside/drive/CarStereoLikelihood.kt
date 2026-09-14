package nyc.curbside.drive

/** How sure we can be, from what a paired Bluetooth device says about itself, that it is a car. */
enum class CarLikelihood { CAR, MAYBE, OTHER }

/**
 * Picks the cars out of a phone's paired devices without asking the user to.
 *
 * Nothing here matches on names. A Bluetooth device declares a class of device, and the minor class
 * has a value reserved for car audio that no headphone uses; devices also advertise the profiles
 * they support, and the ones a dashboard needs to show your messages are not ones earbuds ask for.
 *
 * Kept here, away from Android, because it is a heuristic about the real world and real head units
 * are careless about declaring themselves — it will need tuning against whatever the next car
 * reports, and tuning is safe only with the corpus in [CarStereoLikelihoodTest] to check against.
 */
object CarStereoLikelihood {

    // Minor device classes, from the Bluetooth assigned numbers. These match
    // android.bluetooth.BluetoothClass.Device, restated so this stays testable off-device.
    const val AUDIO_VIDEO_WEARABLE_HEADSET = 0x0404
    const val AUDIO_VIDEO_HANDSFREE = 0x0408
    const val AUDIO_VIDEO_HEADPHONES = 0x0418
    const val AUDIO_VIDEO_CAR_AUDIO = 0x0420

    /**
     * Profiles that mean a dashboard rather than a headset.
     *
     * Observed on every car in the corpus and on none of the headphones: cars want your messages
     * and contacts on the screen, earbuds have nowhere to put them. Message Notification Server is
     * the one that actually turns up in practice — the phonebook and message *server* UUIDs are
     * what the phone offers, not what the car advertises back.
     */
    val DASHBOARD_SERVICES: Set<String> = setOf(
        "00001133", // Message Notification Server
        "00001132", // Message Access Server
        "0000112f", // Phonebook Access Server
    )

    /**
     * @param deviceClass the device's minor class, as `BluetoothClass.getDeviceClass()` returns it.
     * @param services the advertised service UUIDs, in any case; only their first block is read.
     */
    fun of(deviceClass: Int?, services: List<String>): CarLikelihood {
        val dashboard = services.any { uuid ->
            DASHBOARD_SERVICES.any { uuid.lowercase().startsWith(it) }
        }

        return when (deviceClass) {
            AUDIO_VIDEO_CAR_AUDIO -> CarLikelihood.CAR
            // Hands-free alone is not enough: speakerphones and some headsets report it too. With a
            // dashboard profile beside it, it is a car — that combination is what "myBuick" looks
            // like, and no headphone in the corpus comes close to it.
            AUDIO_VIDEO_HANDSFREE -> if (dashboard) CarLikelihood.CAR else CarLikelihood.MAYBE
            else -> CarLikelihood.OTHER
        }
    }
}
