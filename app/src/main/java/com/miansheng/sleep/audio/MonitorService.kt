package com.miansheng.sleep.audio

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miansheng.sleep.*
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class LiveMonitor(
    val sessionId: String? = null, val startedAt: Long = 0, val effectiveMs: Long = 0,
    val state: String = SessionState.FINISHED, val message: String = "", val stopping: Boolean = false,
    val currentLevel: LevelReading? = null, val levels: LevelStats? = null,
    val calibration: SoundCalibration? = null, val levelMessage: String = "未校准 · 相对录音电平",
)

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var requestedState = SessionState.RUNNING
    private var wakeLock: PowerManager.WakeLock? = null
    private val app get() = application as SleepApplication
    private val repo get() = app.repository
    private var session: SleepSession? = null
    private var segment: CaptureSegment? = null
    private var recorder: MeasurementInput? = null
    private val pendingLevels = mutableListOf<SoundLevelFrame>()
    private var lastPersist = 0L

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "夜间监测", NotificationManager.IMPORTANCE_LOW).apply {
                description = "整夜录音状态与暂停操作"; setSound(null, null)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START && job?.isActive != true) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            PAUSE -> requestedState = SessionState.PAUSED
            RESUME -> requestedState = SessionState.RUNNING
            STOP -> { requestedState = SessionState.FINISHED; live.value = live.value.copy(stopping = true) }
            START -> if (job?.isActive != true) {
                requestedState = SessionState.RUNNING
                live.value = LiveMonitor()
                startForeground(NOTIFICATION, notification("正在准备监测"))
                job = scope.launch { monitor() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun monitor() {
        var classifier: SoundClassifier? = null
        var assembler = EventAssembler()
        var ring = AudioRing(16000 * 132)
        var captureAnchor = 0L
        var meter = SoundLevelMeter()
        var configKey: String? = null
        val rawStats = LiveLevelAccumulator()
        val weightedStats = LiveLevelAccumulator()
        var calibrationConsistent = true
        try {
            app.ready.await()
            val calibration = repo.activeCalibration()
            classifier = SoundClassifier(this)
            check(classifier.windowSize == 15600) { "Unexpected audio model input" }
            val start = System.currentTimeMillis()
            session = SleepSession(startedAt = start)
            repo.dao.insertSession(session!!)
            live.value = LiveMonitor(session!!.id, start, state = SessionState.RUNNING)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miansheng:audio").apply { acquire(12 * 60 * 60 * 1000L) }
            val pcm = ShortArray(classifier.windowSize)
            while (currentCoroutineContext().isActive && requestedState != SessionState.FINISHED) {
                if (requestedState == SessionState.PAUSED) {
                    if (session!!.state != SessionState.PAUSED) {
                        assembler.flush()?.let { saveDetection(it, ring, captureAnchor) }
                        releaseRecorder()
                        transition(SessionState.PAUSED, "手动暂停，此时段未录音")
                    }
                    delay(200); continue
                }
                try {
                    if (recorder == null) {
                        recorder = MeasurementInput(this)
                        meter = SoundLevelMeter(); configKey = null
                        ring = AudioRing(16000 * 132); assembler = EventAssembler()
                        captureAnchor = System.currentTimeMillis()
                        transition(SessionState.RUNNING)
                    }
                    if (!recorder!!.read(pcm) { requestedState == SessionState.RUNNING && scope.isActive }) continue
                    check(repo.recordingsDir.usableSpace > 20 * 1024 * 1024) { "存储空间不足，请清理后继续" }
                    val prediction = classifier.classify(pcm)
                    val offset = ring.written
                    ring.append(pcm)
                    val currentKey = recorder!!.configKey()
                    if (currentKey != configKey) { configKey = currentKey; meter = SoundLevelMeter() }
                    val matched = calibration != null && currentKey != null && currentKey == calibration.configKey
                    val reading = meter.accept(pcm)
                    if (reading != null) {
                        pendingLevels += SoundLevelFrame(session!!.id, captureAnchor+offset*1000/16000,
                            dbfs = reading.dbfs, aDbfs = reading.aDbfs, status = reading.status,
                            calibrationId = calibration?.id.takeIf { matched })
                        if (reading.status == "valid") {
                            rawStats.add(reading.dbfs!!)
                            weightedStats.add(reading.aDbfs!!)
                            if (!matched) calibrationConsistent = false
                        }
                    }
                    val usableCalibration = calibration.takeIf { matched && calibrationConsistent }
                    live.value = live.value.copy(currentLevel = reading,
                        levels = if (usableCalibration != null) weightedStats.stats()?.let { it.copy(mean = it.mean+usableCalibration.offset,
                            maximum = it.maximum+usableCalibration.offset, p95 = it.p95+usableCalibration.offset) } else rawStats.stats(),
                        calibration = usableCalibration, levelMessage = when {
                            calibration == null -> "未校准 · 相对录音电平"
                            !matched -> "麦克风或录音配置与校准不匹配 · 显示 dBFS"
                            !calibrationConsistent -> "本次含未校准时段 · 汇总显示 dBFS"
                            else -> "已校准 · 估计 dBA · 16 kHz 限带"
                        })
                    assembler.accept(prediction, offset, pcm.size).forEach { saveDetection(it, ring, captureAnchor) }
                    session = session!!.copy(effectiveMs = session!!.effectiveMs + pcm.size * 1000L / 16000)
                    live.value = live.value.copy(effectiveMs = session!!.effectiveMs)
                    if (SystemClock.elapsedRealtime() - lastPersist > 5000) persist()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    assembler.flush()?.let { runCatching { saveDetection(it, ring, captureAnchor) } }
                    releaseRecorder()
                    transition(SessionState.INTERRUPTED, error.message ?: "录音暂不可用")
                    delay(3000)
                }
            }
            assembler.flush()?.let { saveDetection(it, ring, captureAnchor) }
        } catch (error: Exception) {
            if (error !is CancellationException) live.value = live.value.copy(message = error.message ?: "监测无法启动")
        } finally {
            withContext(NonCancellable) {
                releaseRecorder()
                classifier?.close()
                val end = System.currentTimeMillis()
                segment?.let { repo.dao.closeSegment(it.id, end) }
                session?.let { persistLevels(); repo.dao.finishCapture(it.id, it.effectiveMs, end) }
                live.value = live.value.copy(state = SessionState.FINISHED, stopping = false)
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun transition(state: String, reason: String = "") {
        if (session?.state == state && segment != null) return
        val now = System.currentTimeMillis()
        segment?.let { repo.dao.closeSegment(it.id, now) }
        segment = CaptureSegment(sessionId = session!!.id, startedAt = now,
            kind = when (state) { SessionState.RUNNING -> "active"; SessionState.PAUSED -> "pause"; else -> "gap" }, reason = reason)
        repo.dao.insertSegment(segment!!)
        session = session!!.copy(state = state)
        live.value = live.value.copy(state = state, message = reason, currentLevel = null)
        persist()
    }

    private suspend fun persist() {
        persistLevels()
        val now = System.currentTimeMillis()
        session = session!!.copy(heartbeatAt = now)
        repo.dao.updateCapture(session!!.id, session!!.state, session!!.effectiveMs, now)
        lastPersist = SystemClock.elapsedRealtime()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
    }

    private suspend fun persistLevels() {
        if (pendingLevels.isNotEmpty()) { repo.dao.insertLevels(pendingLevels.toList()); pendingLevels.clear() }
    }

    private suspend fun saveDetection(detection: Detection, ring: AudioRing, anchor: Long) {
        val id = java.util.UUID.randomUUID().toString()
        val start = maxOf(0, detection.startSample - 16000 * 2, ring.written - 16000 * 132)
        val end = minOf(ring.written, detection.endSample + 16000 * 2)
        val pcm = ring.slice(start, end)
        val destination = File(repo.recordingsDir, "$id.m4a")
        var clipState = "available"
        try { ClipWriter.write(destination, pcm) } catch (_: Exception) { clipState = "failed"; destination.delete() }
        repo.dao.insertEvent(SoundEvent(id = id, sessionId = session!!.id,
            startedAt = anchor + detection.startSample * 1000 / 16000,
            endedAt = anchor + detection.endSample * 1000 / 16000,
            kind = detection.kind, detectedMs = detection.detectedSamples * 1000 / 16000,
            confidence = detection.confidence, rms = detection.rms,
            clipPath = destination.absolutePath.takeIf { clipState == "available" }, clipState = clipState,
            waveform = ClipWriter.waveform(pcm)))
    }

    private fun releaseRecorder() { recorder?.close(); recorder = null }

    private fun notification(title: String? = null): Notification {
        val paused = requestedState == SessionState.PAUSED
        val open = Intent(this, MainActivity::class.java).putExtra("monitor", true)
        val content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = Intent(this, MonitorService::class.java).setAction(if (paused) RESUME else PAUSE)
        val toggle = PendingIntent.getService(this, 1, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("confirmEnd", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(title ?: when (live.value.state) { SessionState.PAUSED -> "已暂停监测"; SessionState.INTERRUPTED -> "录音暂不可用，正在重试"; else -> "正在监测" })
            .setContentText("有效监测 ${durationText(live.value.effectiveMs)} · 点击返回眠声")
            .setContentIntent(content).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) "继续监测" else "暂停", toggle).addAction(0, "结束监测", end).build()
    }

    override fun onDestroy() { job?.cancel(); super.onDestroy() }
    companion object {
        const val START = "com.miansheng.START"
        const val PAUSE = "com.miansheng.PAUSE"
        const val RESUME = "com.miansheng.RESUME"
        const val STOP = "com.miansheng.STOP"
        private const val CHANNEL = "night-monitor"
        private const val NOTIFICATION = 101
        val live = MutableStateFlow(LiveMonitor())
        fun command(context: Context, action: String) {
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            if (action == START) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
}
