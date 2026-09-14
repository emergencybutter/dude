package nyc.curbside.drive

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class VehicleIdentifierTest {

    private val golf = VehicleCandidate(id = "golf", bluetoothAddress = "AA:BB:CC:DD:EE:01")
    private val civic = VehicleCandidate(id = "civic", bluetoothAddress = "AA:BB:CC:DD:EE:02")

    private fun identify(stereo: String? = null, vararg candidates: VehicleCandidate) =
        VehicleIdentifier.identify(stereo, candidates.toList())

    @Test
    fun `the stereo names the car outright`() {
        assertEquals(
            VehicleIdentification.Identified("civic", VehicleEvidence.STEREO),
            identify(stereo = civic.bluetoothAddress, golf, civic),
        )
    }

    @Test
    fun `the stereo wins even when the drive began beside the other car`() {
        // She walked past your car to get into hers. The stereo is not fooled by that; a fix is.
        val result = identify(
            stereo = civic.bluetoothAddress,
            golf.copy(metersFromDriveOrigin = 3.0),
            civic.copy(metersFromDriveOrigin = 40.0),
        )

        assertEquals(VehicleIdentification.Identified("civic", VehicleEvidence.STEREO), result)
    }

    @Test
    fun `with no stereo, the drive starting where a car was left names it`() {
        val result = identify(
            null,
            golf.copy(metersFromDriveOrigin = 22.0),
            civic.copy(metersFromDriveOrigin = 4_000.0),
        )

        assertEquals(VehicleIdentification.Identified("golf", VehicleEvidence.DRIVE_ORIGIN), result)
    }

    @Test
    fun `two cars on the same block is a question, not a guess`() {
        val result = identify(
            null,
            golf.copy(metersFromDriveOrigin = 12.0),
            civic.copy(metersFromDriveOrigin = 30.0),
        )

        // Both plausible. Naming the nearer one would send someone to the wrong street.
        assertEquals(VehicleIdentification.Ask(listOf("golf", "civic")), result)
    }

    @Test
    fun `a shortlist is ordered by how close each car was`() {
        val result = identify(
            null,
            golf.copy(metersFromDriveOrigin = 60.0),
            civic.copy(metersFromDriveOrigin = 20.0),
        )

        assertEquals(VehicleIdentification.Ask(listOf("civic", "golf")), result)
    }

    @Test
    fun `a drive starting nowhere near either car asks with no shortlist`() {
        val result = identify(
            null,
            golf.copy(metersFromDriveOrigin = 5_000.0),
            civic.copy(metersFromDriveOrigin = 9_000.0),
        )

        assertEquals(VehicleIdentification.Ask(), result)
    }

    @Test
    fun `no fix at the start of the drive is not a match`() {
        // Null is "we do not know", which must never read as "zero metres away".
        assertEquals(VehicleIdentification.Ask(), identify(null, golf, civic))
    }

    @Test
    fun `a stereo we do not recognise falls through to the location`() {
        val result = identify(
            stereo = "99:99:99:99:99:99",
            golf.copy(metersFromDriveOrigin = 10.0),
            civic.copy(metersFromDriveOrigin = 3_000.0),
        )

        assertEquals(VehicleIdentification.Identified("golf", VehicleEvidence.DRIVE_ORIGIN), result)
    }

    @Test
    fun `one car still has to be recognised, not assumed`() {
        // A single registered car does not make every drive that car — it could be a rental.
        assertEquals(VehicleIdentification.Ask(), identify(null, golf.copy(metersFromDriveOrigin = 900.0)))
        assertEquals(
            VehicleIdentification.Identified("golf", VehicleEvidence.DRIVE_ORIGIN),
            identify(null, golf.copy(metersFromDriveOrigin = 9.0)),
        )
    }

    @Test
    fun `no cars set up at all is a question`() {
        assertEquals(VehicleIdentification.Ask(), identify(stereo = "AA:BB:CC:DD:EE:01"))
    }
}
