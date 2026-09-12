package nyc.curbside.asp

import kotlin.math.round

/**
 * Google's encoded-polyline format, at configurable precision.
 *
 * The segment table holds roughly 150k curb geometries. Storing them as JSON coordinate arrays
 * costs several times what the encoded form does, and the decode is fast enough to run over a whole
 * viewport on every map pan. Precision 6 (about 11cm) is used throughout, because precision 5 is
 * coarse enough to visibly shift a curb line off its street at high zoom.
 */
object PolylineCodec {

    const val DEFAULT_PRECISION: Int = 6

    fun encode(points: List<LatLng>, precision: Int = DEFAULT_PRECISION): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var previousLat = 0L
        var previousLon = 0L
        for (p in points) {
            val lat = round(p.lat * factor).toLong()
            val lon = round(p.lon * factor).toLong()
            encodeValue(lat - previousLat, sb)
            encodeValue(lon - previousLon, sb)
            previousLat = lat
            previousLon = lon
        }
        return sb.toString()
    }

    fun decode(encoded: String, precision: Int = DEFAULT_PRECISION): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0L
        var lon = 0L

        while (index < encoded.length) {
            val (dLat, afterLat) = decodeValue(encoded, index) ?: return out
            index = afterLat
            val (dLon, afterLon) = decodeValue(encoded, index) ?: return out
            index = afterLon

            lat += dLat
            lon += dLon
            out += LatLng(lat / factor, lon / factor)
        }
        return out
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }

    /** Returns the decoded delta and the index just past it, or null on truncated input. */
    private fun decodeValue(encoded: String, start: Int): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var result = 0L
        while (true) {
            if (index >= encoded.length) return null
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
            if (shift > 60) return null
        }
        val delta = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return delta to index
    }
}
