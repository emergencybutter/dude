package nyc.curbside.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A place the car was left.
 *
 * Rows are never deleted on "I moved the car" — [clearedAt] is stamped instead, so the history
 * screen can answer "where do I usually find a space on a Tuesday", which turns out to be the
 * second most useful thing the app does.
 */
@Entity(
    tableName = "parking_events",
    indices = [Index("parkedAt"), Index("clearedAt"), Index("vehicleId")],
)
data class ParkingEventEntity(
    @PrimaryKey val id: String,
    val parkedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,

    /** Which of [nyc.curbside.location.FixQuality] produced the coordinates. */
    val fixQuality: String,

    /** The [nyc.curbside.drive.SignalSource] that decided the drive had ended. */
    val endedBy: String,

    val address: String? = null,
    val note: String? = null,
    val photoUri: String? = null,

    /**
     * Which car this is, and how the app decided. Null means it could not tell and has not been
     * told — the home screen asks, and until it is answered the event stands on its own.
     *
     * See [nyc.curbside.drive.VehicleEvidence] for the values: a stereo that named itself, a drive
     * that began where that car was left, or the user saying so.
     */
    val vehicleId: String? = null,
    val vehicleEvidence: String? = null,

    /** The curb this was matched to, if any, and whether the side is trusted. */
    val curbSegmentId: String? = null,
    val curbSideConfirmed: Boolean = false,

    /** Cached from the rule engine at capture time so notifications survive a data refresh. */
    val moveByEpochMillis: Long? = null,

    /** Set when the user drives away. Null means this is where the car is now. */
    val clearedAt: Long? = null,

    /** Null while unsent; set once the household copy is acknowledged by the server. */
    val sharedAt: Long? = null,

    /** Per-event opt out, for the times you would rather not say where you have been. */
    val shareSuppressed: Boolean = false,

    /**
     * Null for the user's own parkings; the household member id for one received from a partner.
     * Received events are read-only and never re-shared.
     */
    val receivedFrom: String? = null,
)

/**
 * One side of one block, with its parsed sign rules.
 *
 * The bounding-box columns exist so a map pan is a single indexed range query rather than a scan
 * over 150k rows. SQLite's R*Tree module would be tidier, but it is not enabled in every Android
 * build, and four indexed doubles are fast enough for a viewport.
 */
@Entity(
    tableName = "curb_segments",
    indices = [Index("minLat", "maxLat"), Index("minLon", "maxLon"), Index("onStreet")],
)
data class CurbSegmentEntity(
    @PrimaryKey val id: String,
    val onStreet: String,
    val fromStreet: String,
    val toStreet: String,

    /** A [nyc.curbside.asp.StreetSide] name. */
    val side: String,

    /** +1 if this curb is right of the geometry's direction, -1 if left. */
    val sideSign: Int,

    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,

    /** Encoded polyline, precision 6. See [nyc.curbside.asp.PolylineCodec]. */
    val geometry: String,

    /** JSON array of [nyc.curbside.data.RegulationDto]. */
    val rulesJson: String,
)
