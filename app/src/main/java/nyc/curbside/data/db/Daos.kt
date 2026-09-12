package nyc.curbside.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ParkingEventDao {

    /** The car's current location: the most recent parking that has not been driven away from. */
    @Query("SELECT * FROM parking_events WHERE clearedAt IS NULL AND receivedFrom IS NULL ORDER BY parkedAt DESC LIMIT 1")
    fun observeCurrent(): Flow<ParkingEventEntity?>

    @Query("SELECT * FROM parking_events WHERE clearedAt IS NULL AND receivedFrom IS NULL ORDER BY parkedAt DESC LIMIT 1")
    suspend fun current(): ParkingEventEntity?

    /** Where a household partner last left their car. */
    @Query("SELECT * FROM parking_events WHERE clearedAt IS NULL AND receivedFrom IS NOT NULL ORDER BY parkedAt DESC")
    fun observeReceived(): Flow<List<ParkingEventEntity>>

    @Query("SELECT * FROM parking_events ORDER BY parkedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int = 100): Flow<List<ParkingEventEntity>>

    @Query("SELECT * FROM parking_events WHERE id = :id")
    suspend fun byId(id: String): ParkingEventEntity?

    @Query("SELECT * FROM parking_events WHERE sharedAt IS NULL AND shareSuppressed = 0 AND receivedFrom IS NULL")
    suspend fun pendingShare(): List<ParkingEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: ParkingEventEntity)

    @Update
    suspend fun update(event: ParkingEventEntity)

    @Query("UPDATE parking_events SET clearedAt = :at WHERE clearedAt IS NULL AND receivedFrom IS NULL")
    suspend fun clearCurrent(at: Long)

    @Query("UPDATE parking_events SET sharedAt = :at WHERE id = :id")
    suspend fun markShared(id: String, at: Long)
}

@Dao
interface CurbSegmentDao {

    /**
     * Every curb overlapping the given box.
     *
     * The predicate is an overlap test, not a containment test: a segment that merely crosses the
     * viewport must still be drawn, or long blocks vanish when you zoom in on their middle.
     */
    @Query(
        """
        SELECT * FROM curb_segments
        WHERE maxLat >= :minLat AND minLat <= :maxLat
          AND maxLon >= :minLon AND minLon <= :maxLon
        LIMIT :limit
        """,
    )
    suspend fun inBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        limit: Int,
    ): List<CurbSegmentEntity>

    @Query("SELECT * FROM curb_segments WHERE id = :id")
    suspend fun byId(id: String): CurbSegmentEntity?

    @Query("SELECT COUNT(*) FROM curb_segments")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<CurbSegmentEntity>)

    @Query("DELETE FROM curb_segments")
    suspend fun clear()
}
