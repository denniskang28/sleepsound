package com.miansheng.sleep.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class SleepRepository(val context: Context, val database: SleepDatabase) {
    val dao = database.dao()
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    val onboarded = MutableStateFlow(preferences.getBoolean("onboarded", false))
    val retentionDays = MutableStateFlow(preferences.getInt("retention", 7))
    val activeCalibrationId = MutableStateFlow(preferences.getString("soundCalibration", null))
    val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    val sessions = dao.observeSessions()
    val events = dao.observeEvents()
    val segments = dao.observeSegments()
    val calibrations = dao.observeCalibrations()
    suspend fun activeCalibration(): SoundCalibration? = activeCalibrationId.value?.let { dao.calibration(it) }
    suspend fun saveCalibration(value: SoundCalibration) {
        dao.insertCalibration(value)
        preferences.edit().putString("soundCalibration", value.id).apply()
        activeCalibrationId.value = value.id
    }
    fun resetCalibration() {
        preferences.edit().remove("soundCalibration").apply()
        activeCalibrationId.value = null
    }
    fun finishOnboarding() { preferences.edit().putBoolean("onboarded", true).apply(); onboarded.value = true }
    suspend fun setRetention(days: Int) {
        preferences.edit().putInt("retention", days).apply(); retentionDays.value = days
        cleanExpired()
    }
    suspend fun favorite(id: String) = database.withTransaction {
        dao.event(id)?.let { dao.updateEvent(it.copy(favorite = !it.favorite)) }
    }
    suspend fun correct(id: String, label: String?) = database.withTransaction {
        dao.event(id)?.let { dao.updateEvent(it.copy(correction = label)) }
    }
    suspend fun annotate(id: String, tags: List<String>, note: String) = database.withTransaction {
        dao.session(id)?.let { dao.updateSession(it.copy(tags = tags, note = note)) }
    }
    suspend fun feel(id: String, feeling: String?) = database.withTransaction {
        dao.session(id)?.let { dao.updateSession(it.copy(feeling = feeling)) }
    }
    suspend fun deleteClip(id: String, reason: String = "deleted") = withContext(Dispatchers.IO) {
        database.withTransaction {
            val event = dao.event(id) ?: return@withTransaction
            event.clipPath?.let { path ->
                val file = File(path)
                check(!file.exists() || file.delete()) { "录音文件暂时无法删除，请重试" }
            }
            dao.updateEvent(event.copy(clipPath = null, clipState = reason))
        }
    }
    suspend fun clearUnfavorited() = withContext(Dispatchers.IO) {
        // Recheck favorites inside each transaction so a newly favorited clip is protected.
        dao.events().filter { !it.favorite && it.available }.forEach { event ->
            database.withTransaction {
                if (dao.event(event.id)?.favorite == false) deleteClip(event.id, "cleaned")
            }
        }
    }
    suspend fun cleanExpired(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val days = retentionDays.value
        if (days == 0) return@withContext
        val cutoff = now - days * 86400000L
        dao.events().filter { it.startedAt < cutoff && !it.favorite && it.available }.forEach { event ->
            database.withTransaction {
                if (dao.event(event.id)?.favorite == false) deleteClip(event.id, "expired")
            }
        }
    }
    suspend fun markMissingClips() = withContext(Dispatchers.IO) {
        dao.events().filter { it.available && !File(it.clipPath!!).isFile }.forEach { event ->
            dao.updateEvent(event.copy(clipPath = null, clipState = "missing"))
        }
    }
    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        dao.events().filter { it.sessionId == id }.forEach { deleteClip(it.id) }
        dao.deleteSession(id)
    }
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.events().forEach { deleteClip(it.id) }
        dao.deleteSessions()
        dao.deleteCalibrations()
        resetCalibration()
        recordingsDir.listFiles()?.forEach { check(it.delete()) { "部分录音暂时无法清理" } }
        preferences.edit().putBoolean("onboarded", false).apply(); onboarded.value = false
    }
    suspend fun recoverInterruptedSessions(now: Long = System.currentTimeMillis()) = database.withTransaction {
        dao.unfinished().forEach { session ->
            val last = session.heartbeatAt.coerceAtMost(now)
            dao.segments(session.id).filter { it.endedAt == null }.forEach { dao.closeSegment(it.id, last) }
            dao.trimLevels(session.id, last)
            dao.updateSession(session.copy(endedAt = last, state = SessionState.FINISHED, recovered = true))
        }
        // A crash between committing a clip and its event must not leave private orphan audio indefinitely.
        val referenced = dao.events().mapNotNull { it.clipPath }.toSet()
        recordingsDir.listFiles()?.filter { it.absolutePath !in referenced }?.forEach(File::delete)
    }
}
