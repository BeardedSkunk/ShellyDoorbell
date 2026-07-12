package de.beardedskunk.shellydoorbell.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Ein Klingel-Ereignis. Der Unix-Timestamp (Sekunden) ist zugleich der Schluessel,
 * dadurch dedupliziert der Merge aus dem Shelly-KVS-Ringpuffer automatisch.
 */
@Entity(tableName = "ring_events")
data class RingEvent(
    @PrimaryKey val ts: Long,
    val power: Double? = null,
)

@Dao
interface RingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<RingEvent>)

    @Query("SELECT * FROM ring_events ORDER BY ts DESC")
    fun all(): Flow<List<RingEvent>>

    @Query("SELECT * FROM ring_events WHERE ts >= :since ORDER BY ts DESC LIMIT :limit")
    fun recent(since: Long, limit: Int): Flow<List<RingEvent>>

    @Query("DELETE FROM ring_events WHERE ts < :cutoff")
    suspend fun prune(cutoff: Long)
}

@Database(entities = [RingEvent::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun ringDao(): RingDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "doorbell.db")
                .build()
                .also { instance = it }
        }
    }
}
