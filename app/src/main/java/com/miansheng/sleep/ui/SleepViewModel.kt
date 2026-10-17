package com.miansheng.sleep.ui

import android.app.Application
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.BatteryManager
import android.os.StatFs
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.miansheng.sleep.SleepApplication
import com.miansheng.sleep.audio.*
import com.miansheng.sleep.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

enum class Screen { ONBOARD, HOME, PRECHECK, DENIED, MONITOR, GENERATING, REPORT, RECORDINGS, HISTORY, TRENDS, SETTINGS, HELP }
data class AppData(val sessions: List<SleepSession> = emptyList(), val events: List<SoundEvent> = emptyList(),
                   val segments: List<CaptureSegment> = emptyList(), val live: LiveMonitor = LiveMonitor())
data class Precheck(val checking: Boolean = true, val micOk: Boolean = false, val battery: Int = 0,
                    val freeMb: Long = 0, val noisy: Boolean = false, val error: String? = null)

class SleepViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SleepApplication
    val repo = app.repository
    val data = combine(repo.sessions, repo.events, repo.segments, MonitorService.live) { s, e, g, l -> AppData(s, e, g, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppData())
    val retention = repo.retentionDays
    var screen by mutableStateOf(Screen.HOME)
    var loaded by mutableStateOf(false)
    var selectedSessionId by mutableStateOf<String?>(null)
    var reportFrom by mutableStateOf(Screen.HOME)
    var sheet by mutableStateOf<String?>(null)
    var filter by mutableStateOf("all")
    var trendRange by mutableIntStateOf(7)
    var selectedTrendDate by mutableStateOf<java.time.LocalDate?>(null)
    var selectedTimelineId by mutableStateOf<String?>(null)
    var precheck by mutableStateOf(Precheck())
    var playerEventId by mutableStateOf<String?>(null)
    var playerExpanded by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0)
    var durationMs by mutableLongStateOf(0)
    var draftTags by mutableStateOf<List<String>>(emptyList())
    var draftNote by mutableStateOf("")
    var correctionChoice by mutableStateOf<String?>(null)
    var snackbar by mutableStateOf<String?>(null)
    var undo by mutableStateOf<(() -> Unit)?>(null)
    private var snackJob: Job? = null
    private var checkJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            app.ready.await()
            if (screen == Screen.HOME) screen = if (repo.onboarded.value) Screen.HOME else Screen.ONBOARD
            loaded = true
        }
        viewModelScope.launch {
            while (isActive) {
                if (mediaPlayer != null) {
                    runCatching { positionMs = mediaPlayer!!.currentPosition.toLong(); playing = mediaPlayer!!.isPlaying }
                }
                delay(200)
            }
        }
    }

    fun task(block: suspend () -> Unit) { viewModelScope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (error: Exception) { if (error !is CancellationException) snack(error.message ?: "操作未完成，请重试") }
    } }
    fun snack(text: String, undoAction: (() -> Unit)? = null) {
        snackJob?.cancel(); snackbar = text; undo = undoAction
        snackJob = viewModelScope.launch { delay(5000); snackbar = null; undo = null }
    }
    fun finishOnboard() { repo.finishOnboarding(); screen = Screen.HOME }
    fun tab(target: Screen) { if (screen == Screen.PRECHECK) cancelPrecheck(); screen = target; playerExpanded = false }
    fun openReport(id: String, from: Screen = screen) {
        selectedSessionId = id; reportFrom = from; selectedTimelineId = null; screen = Screen.REPORT; playerExpanded = false
    }
    fun back() {
        when {
            sheet != null -> sheet = null
            playerExpanded -> playerExpanded = false
            screen == Screen.RECORDINGS -> screen = Screen.REPORT
            screen == Screen.REPORT -> screen = reportFrom
            screen == Screen.PRECHECK -> { cancelPrecheck(); screen = Screen.HOME }
            screen == Screen.HELP -> screen = Screen.SETTINGS
            else -> screen = Screen.HOME
        }
    }

    fun runPrecheck() {
        checkJob?.cancel(); screen = Screen.PRECHECK; precheck = Precheck()
        checkJob = viewModelScope.launch {
            precheck = withContext(Dispatchers.IO) {
                val battery = (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val free = StatFs(app.filesDir.path).availableBytes / 1048576
                var record: AudioRecord? = null
                try {
                    check(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
                    record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, 32000)
                    check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风暂不可用" }
                    record.startRecording()
                    val audio = ShortArray(16000)
                    var filled = 0
                    while (filled < audio.size && isActive) {
                        val n = record.read(audio, filled, audio.size - filled, AudioRecord.READ_BLOCKING)
                        check(n > 0) { "无法读取麦克风" }; filled += n
                    }
                    ensureActive()
                    val energy = audio.sumOf { val value = it / 32768.0; value * value } / audio.size
                    Precheck(false, true, battery, free, kotlin.math.sqrt(energy) > .035)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Precheck(false, false, battery, free, error = error.message ?: "麦克风暂不可用")
                } finally { record?.let { runCatching { it.stop() }; it.release() } }
            }
        }
    }
    fun cancelPrecheck() { checkJob?.cancel(); checkJob = null }
    fun startMonitoring() {
        if (precheck.checking || !precheck.micOk || precheck.freeMb < 50) return
        closePlayer(); cancelPrecheck()
        try { MonitorService.command(app, MonitorService.START); screen = Screen.MONITOR }
        catch (_: Exception) { snack("无法启动录音，请检查麦克风权限后重试") }
    }
    fun togglePause() {
        val paused = MonitorService.live.value.state == SessionState.PAUSED
        MonitorService.command(app, if (paused) MonitorService.RESUME else MonitorService.PAUSE)
    }
    fun finishMonitoring() {
        sheet = null; screen = Screen.GENERATING
        val id = MonitorService.live.value.sessionId
        MonitorService.command(app, MonitorService.STOP)
        viewModelScope.launch {
            MonitorService.live.first { it.state == SessionState.FINISHED && !it.stopping }
            if (id != null) openReport(id, Screen.HOME) else { screen = Screen.HOME; snack("本次未能采集有效数据") }
        }
    }

    fun openPlayer(event: SoundEvent, autoPlay: Boolean = false) {
        if (!event.available || !File(event.clipPath!!).isFile) { snack(clipMessage(event)); task { repo.markMissingClips() }; return }
        if (MonitorService.live.value.state != SessionState.FINISHED) { snack("请先结束监测，再回听录音，以免影响采集"); return }
        if (playerEventId == event.id && mediaPlayer != null) { playerExpanded = true; if (autoPlay) togglePlayback(); return }
        closePlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(event.clipPath); prepare()
                setOnCompletionListener { playing = false; positionMs = durationMs }
                setOnErrorListener { _, _, _ -> snack("录音暂时无法播放"); closePlayer(); true }
            }
            playerEventId = event.id; playerExpanded = true; durationMs = mediaPlayer!!.duration.toLong(); positionMs = 0
            if (autoPlay) togglePlayback()
        } catch (_: Exception) { closePlayer(); snack("录音文件不可用") }
    }
    fun togglePlayback() {
        mediaPlayer?.let { p ->
            runCatching {
                if (p.isPlaying) p.pause() else { if (positionMs >= durationMs) p.seekTo(0); p.start() }
                playing = p.isPlaying
            }.onFailure { snack("录音暂时无法播放") }
        }
    }
    fun seek(milliseconds: Long) {
        positionMs = milliseconds.coerceIn(0, durationMs)
        runCatching { mediaPlayer?.seekTo(positionMs.toInt()) }
    }
    fun skip(direction: Int) {
        val all = data.value.events.filter { it.sessionId == data.value.events.find { it.id == playerEventId }?.sessionId && it.available }
        val index = all.indexOfFirst { it.id == playerEventId }
        all.getOrNull(index + direction)?.let { openPlayer(it, playing) }
    }
    fun closePlayer() { runCatching { mediaPlayer?.release() }; mediaPlayer = null; playerEventId = null; playing = false; playerExpanded = false }
    fun pausePlayback() { if (playing) runCatching { mediaPlayer?.pause() }; playing = false }
    fun correct(event: SoundEvent) {
        val choice = correctionChoice ?: return
        viewModelScope.launch {
            runCatching { repo.correct(event.id, choice) }
                .onSuccess {
                    sheet = null
                    snack("已修正，报告统计已更新") { task { repo.correct(event.id, event.correction) }; snackbar = null }
                }.onFailure { snack("修正未保存，请重试") }
        }
    }
    fun saveTags(session: SleepSession) {
        val tags = draftTags; val note = draftNote.trim().take(200)
        viewModelScope.launch {
            runCatching { repo.annotate(session.id, tags, note) }
                .onSuccess { sheet = null; snack("标签已保存") }
                .onFailure { snack("标签未保存，请重试") }
        }
    }
    fun deleteRecording(event: SoundEvent) {
        closePlayer(); sheet = null
        viewModelScope.launch {
            runCatching { repo.deleteClip(event.id) }.onSuccess { snack("录音已删除，统计数据保留") }.onFailure { snack("删除未完成，请重试") }
        }
    }
    fun cleanRecordings() {
        val current = data.value.events.find { it.id == playerEventId }
        if (current?.favorite != true) closePlayer()
        sheet = null
        viewModelScope.launch {
            runCatching { repo.clearUnfavorited() }.onSuccess { snack("已清理未收藏录音，统计数据保留") }.onFailure { snack("部分录音清理未完成，请重试") }
        }
    }
    fun clearAllData() {
        if (MonitorService.live.value.state != SessionState.FINISHED) { sheet = null; snack("请先结束当前监测，再清除数据"); return }
        closePlayer(); sheet = null
        viewModelScope.launch {
            runCatching { repo.clearAll() }.onSuccess { screen = Screen.ONBOARD; snack("已清除全部数据") }.onFailure { snack("部分数据未能清理，请重试") }
        }
    }
    fun deleteSession(session: SleepSession) {
        closePlayer(); sheet = null
        viewModelScope.launch {
            runCatching { repo.deleteSession(session.id) }.onSuccess { screen = Screen.HISTORY; snack("已删除本次记录与相关录音") }.onFailure { snack("删除未完成，请重试") }
        }
    }
    override fun onCleared() { closePlayer(); cancelPrecheck(); super.onCleared() }
}

fun clipMessage(event: SoundEvent) = when (event.clipState) {
    "expired" -> "录音已按设置清理"
    "cleaned" -> "录音已清理"
    "deleted" -> "录音已删除，统计数据保留"
    "failed" -> "录音保存失败，声音事件仍然保留"
    else -> "录音文件不可用"
}
