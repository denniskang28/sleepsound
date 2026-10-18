package com.miansheng.sleep.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object SessionState {
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val INTERRUPTED = "interrupted"
    const val FINISHED = "finished"
}

@Entity(tableName = "sessions")
data class SleepSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val zoneId: String = ZoneId.systemDefault().id,
    val state: String = SessionState.RUNNING,
    val effectiveMs: Long = 0,
    val heartbeatAt: Long = startedAt,
    val recovered: Boolean = false,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val feeling: String? = null,
) {
    val date: LocalDate get() = Instant.ofEpochMilli(startedAt).atZone(ZoneId.of(zoneId)).toLocalDate()
    val insufficient: Boolean get() = effectiveMs < 3 * 60 * 60 * 1000L
}

@Entity(tableName = "events", foreignKeys = [ForeignKey(
    entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("sessionId")])
data class SoundEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val kind: String,
    val detectedMs: Long,
    val confidence: Float,
    val rms: Float,
    val clipPath: String? = null,
    val clipState: String = "available",
    val favorite: Boolean = false,
    val correction: String? = null,
    val waveform: List<Float> = emptyList(),
) {
    val label: String get() = correction?.let { "已修正：$it" } ?: if (kind == "snore") "鼾声" else "疑似梦话"
    val strength: String get() = when { rms >= .08f -> "强"; rms >= .02f -> "中"; else -> "轻" }
    val available: Boolean get() = clipState == "available" && clipPath != null
}

@Entity(tableName = "segments", foreignKeys = [ForeignKey(
    entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("sessionId")])
data class CaptureSegment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val kind: String,
    val reason: String = "",
)

@Entity(tableName = "sound_levels", primaryKeys = ["sessionId", "startedAt"], foreignKeys = [ForeignKey(
    entity = SleepSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE,
)])
data class SoundLevelFrame(
    val sessionId: String,
    val startedAt: Long,
    val durationMs: Long = 975,
    val dbfs: Double?,
    val aDbfs: Double?,
    val status: String = "valid",
    val calibrationId: String? = null,
)

@Entity(tableName = "sound_calibrations")
data class SoundCalibration(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val configKey: String,
    val deviceLabel: String,
    val referenceMeter: String,
    val offset: Double,
    val minimum: Double,
    val maximum: Double,
    val maxError: Double,
    val pointsJson: String,
)

class Converters {
    @TypeConverter fun stringsToJson(values: List<String>): String = JSONArray(values).toString()
    @TypeConverter fun stringsFromJson(value: String): List<String> = JSONArray(value).let { a -> List(a.length()) { a.getString(it) } }
    @TypeConverter fun floatsToJson(values: List<Float>): String = JSONArray(values).toString()
    @TypeConverter fun floatsFromJson(value: String): List<Float> = JSONArray(value).let { a -> List(a.length()) { a.getDouble(it).toFloat() } }
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun observeSessions(): Flow<List<SleepSession>>
    @Query("SELECT * FROM events ORDER BY startedAt") fun observeEvents(): Flow<List<SoundEvent>>
    @Query("SELECT * FROM segments ORDER BY startedAt") fun observeSegments(): Flow<List<CaptureSegment>>
    @Query("SELECT * FROM sound_levels WHERE sessionId = :sessionId ORDER BY startedAt") fun observeLevels(sessionId: String): Flow<List<SoundLevelFrame>>
    @Query("SELECT * FROM sound_levels WHERE sessionId = :sessionId ORDER BY startedAt") suspend fun levels(sessionId: String): List<SoundLevelFrame>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLevels(values: List<SoundLevelFrame>)
    @Query("SELECT * FROM sound_calibrations ORDER BY createdAt DESC") fun observeCalibrations(): Flow<List<SoundCalibration>>
    @Query("SELECT * FROM sound_calibrations WHERE id = :id") suspend fun calibration(id: String): SoundCalibration?
    @Insert suspend fun insertCalibration(value: SoundCalibration)
    @Query("DELETE FROM sound_calibrations") suspend fun deleteCalibrations()
    @Query("DELETE FROM sound_levels WHERE sessionId = :sessionId AND startedAt + durationMs > :end") suspend fun trimLevels(sessionId: String, end: Long)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SleepSession?
    @Query("SELECT * FROM sessions WHERE state != 'finished'") suspend fun unfinished(): List<SleepSession>
    @Query("SELECT * FROM events") suspend fun events(): List<SoundEvent>
    @Query("SELECT * FROM events WHERE id = :id") suspend fun event(id: String): SoundEvent?
    @Query("SELECT * FROM segments WHERE sessionId = :id ORDER BY startedAt") suspend fun segments(id: String): List<CaptureSegment>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSession(value: SleepSession)
    @Update suspend fun updateSession(value: SleepSession)
    @Query("UPDATE sessions SET state = :state, effectiveMs = :effective, heartbeatAt = :heartbeat WHERE id = :id")
    suspend fun updateCapture(id: String, state: String, effective: Long, heartbeat: Long)
    @Query("UPDATE sessions SET state = 'finished', effectiveMs = :effective, heartbeatAt = :end, endedAt = :end WHERE id = :id")
    suspend fun finishCapture(id: String, effective: Long, end: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvent(value: SoundEvent)
    @Update suspend fun updateEvent(value: SoundEvent)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSegment(value: CaptureSegment)
    @Query("UPDATE segments SET endedAt = :end WHERE id = :id") suspend fun closeSegment(id: String, end: Long)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM sessions") suspend fun deleteSessions()
}

@Database(entities = [SleepSession::class, SoundEvent::class, CaptureSegment::class, SoundLevelFrame::class, SoundCalibration::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun dao(): SleepDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sound_levels` (`sessionId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `dbfs` REAL, `aDbfs` REAL, `status` TEXT NOT NULL, `calibrationId` TEXT, PRIMARY KEY(`sessionId`, `startedAt`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sound_calibrations` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `configKey` TEXT NOT NULL, `deviceLabel` TEXT NOT NULL, `referenceMeter` TEXT NOT NULL, `offset` REAL NOT NULL, `minimum` REAL NOT NULL, `maximum` REAL NOT NULL, `maxError` REAL NOT NULL, `pointsJson` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, SleepDatabase::class.java, "miansheng.db").addMigrations(MIGRATION_1_2).build()
    }
}
