package nyc.curbside.data

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * One of the household's cars.
 *
 * ## Why the id is derived from the stereo
 *
 * Two phones have to agree on what "the Golf" is without a pairing step of their own, or a shared
 * parking event is just another pin with a name on it. The one fact both phones independently
 * possess is the stereo's Bluetooth address — they are each paired with it, or they could not have
 * detected a drive in that car at all. Hashing it gives the same id on both handsets, derived
 * rather than agreed.
 *
 * A car with no stereo can still be added by hand, but its id is local to the phone that made it,
 * so it will not line up with the same car on a partner's phone. That is a real limitation and the
 * settings screen says so rather than hiding it.
 */
@Serializable
data class Vehicle(
    val id: String,
    val name: String,
    /** The stereo nominated for this car. Null for one added by hand. */
    val bluetoothAddress: String? = null,
) {
    val isShareable: Boolean get() = bluetoothAddress != null

    companion object {

        /** The same car gives the same id on every phone paired with its stereo. */
        fun idFor(bluetoothAddress: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bluetoothAddress.uppercase().toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }

        fun fromStereo(address: String, name: String): Vehicle =
            Vehicle(id = idFor(address), name = name, bluetoothAddress = address)
    }
}
