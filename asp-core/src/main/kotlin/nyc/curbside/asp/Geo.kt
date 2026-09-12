package nyc.curbside.asp

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    operator fun contains(p: LatLng): Boolean =
        p.lat in minLat..maxLat && p.lon in minLon..maxLon

    /** Grows the box by [meters] on all sides, for "find me everything near here" queries. */
    fun expandedBy(meters: Double): BoundingBox {
        val dLat = meters / Geo.METERS_PER_DEGREE_LAT
        val midLat = (minLat + maxLat) / 2
        val dLon = meters / (Geo.METERS_PER_DEGREE_LAT * cos(Math.toRadians(midLat)))
        return BoundingBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    companion object {
        fun around(center: LatLng, radiusMeters: Double): BoundingBox =
            BoundingBox(center.lat, center.lon, center.lat, center.lon).expandedBy(radiusMeters)

        fun of(points: List<LatLng>): BoundingBox {
            require(points.isNotEmpty()) { "cannot bound an empty geometry" }
            var minLat = Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (p in points) {
                minLat = min(minLat, p.lat)
                minLon = min(minLon, p.lon)
                maxLat = max(maxLat, p.lat)
                maxLon = max(maxLon, p.lon)
            }
            return BoundingBox(minLat, minLon, maxLat, maxLon)
        }
    }
}

/**
 * Small-scale geodesy, good enough for a city block.
 *
 * Everything here works in a local equirectangular projection anchored at the point of interest.
 * Over the few hundred metres this app ever measures, the error against a proper geodesic is well
 * under a centimetre, and the arithmetic is cheap enough to run over a viewport of segments on
 * every map pan.
 */
object Geo {

    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    /** One degree of latitude, anywhere. Longitude is scaled by cos(latitude) at the point of use. */
    const val METERS_PER_DEGREE_LAT: Double = Math.PI * EARTH_RADIUS_METERS / 180.0

    /** Great-circle distance. Used where accuracy matters more than speed — walking distances. */
    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceAtMost(1.0)))
    }

    /**
     * Projects [p] into metres east/north of [origin]. The caller does all its geometry in this
     * flat frame, which keeps the segment maths to plain vector algebra.
     */
    fun toLocalMeters(origin: LatLng, p: LatLng): Pair<Double, Double> {
        val east = (p.lon - origin.lon) * METERS_PER_DEGREE_LAT * cos(Math.toRadians(origin.lat))
        val north = (p.lat - origin.lat) * METERS_PER_DEGREE_LAT
        return east to north
    }

    /** Where along a polyline the given point falls, and how far off it is. */
    data class Projection(
        val distanceMeters: Double,
        /** Index of the polyline vertex that starts the closest span. */
        val segmentIndex: Int,
        /** Position along that span, 0 at the start vertex and 1 at the end. */
        val t: Double,
        val closest: LatLng,
        /**
         * Which hand of the polyline the point lies on, following the direction the line is drawn:
         * +1 to the right, -1 to the left, 0 when the point sits exactly on the line.
         */
        val side: Int,
    )

    /**
     * Closest approach from [point] to [line]. Returns null for a degenerate line of fewer than
     * two vertices.
     */
    fun project(point: LatLng, line: List<LatLng>): Projection? {
        if (line.size < 2) return null

        var best: Projection? = null
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val (ax, ay) = toLocalMeters(point, a)
            val (bx, by) = toLocalMeters(point, b)

            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            // The point is the origin of the local frame, so the vector to it is simply -a.
            val t = if (lengthSquared == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)

            val cx = ax + t * dx
            val cy = ay + t * dy
            val distance = sqrt(cx * cx + cy * cy)

            val incumbent = best
            if (incumbent == null || distance < incumbent.distanceMeters) {
                // Cross product of the span direction with the vector from the span start to the
                // point. Positive means the point is to the left of the direction of travel.
                val cross = dx * (-ay) - dy * (-ax)
                val side = when {
                    abs(cross) < 1e-9 -> 0
                    cross > 0 -> -1
                    else -> 1
                }
                best = Projection(
                    distanceMeters = distance,
                    segmentIndex = i,
                    t = t,
                    closest = fromLocalMeters(point, cx, cy),
                    side = side,
                )
            }
        }
        return best
    }

    private fun fromLocalMeters(origin: LatLng, east: Double, north: Double): LatLng {
        val lat = origin.lat + north / METERS_PER_DEGREE_LAT
        val lon = origin.lon + east / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(origin.lat)))
        return LatLng(lat, lon)
    }
}
