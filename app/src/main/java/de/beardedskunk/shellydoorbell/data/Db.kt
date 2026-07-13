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
 * Ein Klingel-Ereignis: fasst alle Druecke eines "Besuchs" zusammen (siehe
 * doorbell.js, 3-min-Fenster). [ts] ist der Gruppen-Start (Unix-Sekunden) und
 * zugleich der Schluessel — dadurch dedupliziert der Merge aus dem Shelly-KVS
 * automatisch. [authoritative] = false markiert einen nur lokal (aus den
 * empfangenen Alarm-Events) gezaehlten Vorlaeufer, den der spaetere, exakte
 * Datensatz des Scripts ersetzt.
 */
@Entity(tableName = "ring_events")
data class RingEvent(
    @PrimaryKey val ts: Long,
    val count: Int = 1,
    val durationS: Int = 1,
    val authoritative: Boolean = false,
)

@Dao
interface RingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: RingEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<RingEvent>)

    /** Vorlaeufige (lokal gezaehlte) Eintraege im Zeitfenster entfernen. */
    @Query("DELETE FROM ring_events WHERE authoritative = 0 AND ts BETWEEN :from AND :to")
    suspend fun clearProvisional(from: Long, to: Long)

    @Query("SELECT * FROM ring_events ORDER BY ts DESC")
    fun all(): Flow<List<RingEvent>>

    @Query("SELECT * FROM ring_events WHERE ts >= :since ORDER BY ts DESC LIMIT :limit")
    fun recent(since: Long, limit: Int): Flow<List<RingEvent>>

    @Query("DELETE FROM ring_events WHERE ts < :cutoff")
    suspend fun prune(cutoff: Long)
}

@Database(entities = [RingEvent::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun ringDao(): RingDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "doorbell.db")
                // Das Schema hat sich mit v3 des Scripts geaendert (Ereignisse mit
                // Anzahl/Dauer statt Einzel-Timestamps); die alte History wird
                // bewusst verworfen statt migriert.
                .fallbackToDestructiveMigration(true)
                .build()
                .also { instance = it }
        }
    }
}
