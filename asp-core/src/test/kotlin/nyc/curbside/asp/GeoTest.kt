package nyc.curbside.asp

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GeoTest {

    /** A west-to-east run of Bergen Street in Brooklyn, roughly one block. */
    private val bergenStreet = listOf(
        LatLng(40.68210, -73.98010),
        LatLng(40.68212, -73.97880),
    )

    @Test
    fun `haversine matches a known short distance`() {
        // One thousandth of a degree of latitude is about 111 metres, anywhere on earth.
        val d = Geo.haversineMeters(LatLng(40.7, -74.0), LatLng(40.701, -74.0))

        assertTrue(abs(d - 111.19) < 0.5, "expected ~111.2m, got $d")
    }

    @Test
    fun `haversine shrinks a degree of longitude by the cosine of the latitude`() {
        val atEquator = Geo.haversineMeters(LatLng(0.0, 0.0), LatLng(0.0, 0.001))
        val atNyc = Geo.haversineMeters(LatLng(40.7, -74.0), LatLng(40.7, -73.999))

        // cos(40.7 degrees) is about 0.758.
        assertTrue(abs(atNyc / atEquator - 0.758) < 0.01, "ratio was ${atNyc / atEquator}")
    }

    @Test
    fun `a point beside the line projects onto it at the right distance`() {
        // About 20 metres north of the middle of the block.
        val point = LatLng(40.68229, -73.97945)
        val projection = assertNotNull(Geo.project(point, bergenStreet))

        assertTrue(
            abs(projection.distanceMeters - 19.0) < 2.0,
            "expected ~19m, got ${projection.distanceMeters}",
        )
        assertTrue(projection.t > 0.0 && projection.t < 1.0)
    }

    @Test
    fun `side is left for a point north of a west-to-east line`() {
        val north = LatLng(40.68240, -73.97945)
        val south = LatLng(40.68182, -73.97945)

        assertEquals(-1, Geo.project(north, bergenStreet)?.side, "north of an eastbound line is its left")
        assertEquals(1, Geo.project(south, bergenStreet)?.side, "south of an eastbound line is its right")
    }

    @Test
    fun `reversing the line reverses the side`() {
        val north = LatLng(40.68240, -73.97945)

        assertEquals(-1, Geo.project(north, bergenStreet)?.side)
        assertEquals(1, Geo.project(north, bergenStreet.reversed())?.side)
    }

    @Test
    fun `a point beyond the end of the line clamps to the endpoint`() {
        val pastTheEnd = LatLng(40.68212, -73.97800)
        val projection = assertNotNull(Geo.project(pastTheEnd, bergenStreet))

        assertEquals(1.0, projection.t)
        assertTrue(abs(projection.distanceMeters - 67.0) < 3.0, "got ${projection.distanceMeters}")
    }

    @Test
    fun `a degenerate line cannot be projected onto`() {
        assertNull(Geo.project(LatLng(40.0, -74.0), listOf(LatLng(40.0, -74.0))))
    }

    @Test
    fun `a bounding box around a point covers the requested radius`() {
        val center = LatLng(40.7, -74.0)
        val box = BoundingBox.around(center, 100.0)

        assertTrue(LatLng(40.7008, -74.0) in box) // ~89m north
        assertTrue(LatLng(40.7010, -74.0) !in box) // ~111m north, outside
    }

    @Test
    fun `polyline round trips at eleven centimetre precision`() {
        val original = listOf(
            LatLng(40.682103, -73.980107),
            LatLng(40.682121, -73.978804),
            LatLng(40.681950, -73.977500),
        )

        val decoded = PolylineCodec.decode(PolylineCodec.encode(original))

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (a, b) ->
            assertTrue(Geo.haversineMeters(a, b) < 0.2, "lost $a to $b")
        }
    }

    @Test
    fun `decoding truncated input stops cleanly instead of throwing`() {
        val encoded = PolylineCodec.encode(listOf(LatLng(40.68, -73.98), LatLng(40.69, -73.97)))

        val decoded = PolylineCodec.decode(encoded.substring(0, encoded.length - 2))

        assertTrue(decoded.size < 2)
    }
}
