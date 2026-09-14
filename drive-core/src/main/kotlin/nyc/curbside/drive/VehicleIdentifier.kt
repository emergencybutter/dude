package nyc.curbside.drive

/**
 * One of the household's cars, as much as the detector knows about it when a drive ends.
 *
 * @param bluetoothAddress the stereo nominated for this car, if it has one.
 * @param metersFromDriveOrigin how far this car was last left from where the drive that just ended
 *   began. Null when either end of that measurement is unknown — no fix at the start of the drive,
 *   or no record of where this car was.
 */
data class VehicleCandidate(
    val id: String,
    val bluetoothAddress: String? = null,
    val metersFromDriveOrigin: Double? = null,
)

/** How confident the app is allowed to sound about which car this was. */
enum class VehicleEvidence {
    /** The car's own stereo connected. As certain as this app gets about anything. */
    STEREO,

    /** The drive began where this car was parked, and no other car was near enough to confuse it. */
    DRIVE_ORIGIN,

    /** The user said so. */
    STATED,
}

sealed interface VehicleIdentification {

    data class Identified(val vehicleId: String, val evidence: VehicleEvidence) : VehicleIdentification

    /**
     * The car has to be asked about.
     *
     * @param shortlist the cars that were plausible, if any were — two parked within sight of each
     *   other, say. Empty when nothing narrowed it down and every car is equally likely.
     */
    data class Ask(val shortlist: List<String> = emptyList()) : VehicleIdentification
}

/**
 * Works out which car a drive was in.
 *
 * There are two free signals and no third. The stereo says so outright, and it is right whenever it
 * is present. When it is absent — the phone never paired with that car, the stereo was off, the
 * drive was caught by motion alone — the other free fact is where the drive *began*: a car you are
 * driving was, until a moment ago, parked, and the app knows where it left each of them.
 *
 * Neither is available often enough to guess with. Two cars parked on the same block are not
 * distinguishable by a cached fix, and the honest answer there is a question, not a coin toss: a
 * wrong answer here reports the wrong car moved, and sends its owner to the wrong street.
 */
object VehicleIdentifier {

    /**
     * How close the start of a drive has to be to where a car was left to call it that car.
     *
     * Generous, because the fix this is measured against is whatever the phone happened to have
     * cached — no GPS is turned on to answer this question. Being generous costs little: a second
     * car inside the same radius makes the answer a question rather than a wrong name.
     */
    const val ORIGIN_MATCH_METERS: Double = 75.0

    fun identify(
        stereoAddress: String?,
        candidates: List<VehicleCandidate>,
    ): VehicleIdentification {
        if (stereoAddress != null) {
            val byStereo = candidates.filter { it.bluetoothAddress == stereoAddress }
            // Two cars nominated with one address is a user mistake, not something to resolve here.
            if (byStereo.size == 1) {
                return VehicleIdentification.Identified(byStereo.single().id, VehicleEvidence.STEREO)
            }
        }

        val nearby = candidates
            .filter { (it.metersFromDriveOrigin ?: Double.MAX_VALUE) <= ORIGIN_MATCH_METERS }
            .sortedBy { it.metersFromDriveOrigin }

        return when (nearby.size) {
            1 -> VehicleIdentification.Identified(nearby.single().id, VehicleEvidence.DRIVE_ORIGIN)
            0 -> VehicleIdentification.Ask()
            else -> VehicleIdentification.Ask(nearby.map { it.id })
        }
    }
}
