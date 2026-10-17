package com.miansheng.sleep.data

import android.content.Context
import androidx.room.*
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

@Database(entities = [SleepSession::class, SoundEvent::class, CaptureSegment::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun dao(): SleepDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, SleepDatabase::class.java, "miansheng.db").build()
    }
}
