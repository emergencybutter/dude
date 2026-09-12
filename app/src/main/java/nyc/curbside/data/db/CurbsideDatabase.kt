package nyc.curbside.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ParkingEventEntity::class, CurbSegmentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CurbsideDatabase : RoomDatabase() {
    abstract fun parkingEvents(): ParkingEventDao
    abstract fun curbSegments(): CurbSegmentDao

    companion object {
        const val NAME = "curbside.db"
    }
}
