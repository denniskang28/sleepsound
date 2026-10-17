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
)

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var requestedState = SessionState.RUNNING
    @Volatile private var clientSilenced = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val app get() = application as SleepApplication
    private val repo get() = app.repository
    private var session: SleepSession? = null
    private var segment: CaptureSegment? = null
    private var recorder: AudioRecord? = null
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
        try {
            app.ready.await()
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
                        recorder = createRecorder(classifier.windowSize).apply { startRecording() }
                        check(recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风暂不可用" }
                        ring = AudioRing(16000 * 132); assembler = EventAssembler()
                        captureAnchor = System.currentTimeMillis()
                        transition(SessionState.RUNNING)
                    }
                    var filled = 0
                    while (filled < pcm.size && requestedState == SessionState.RUNNING) {
                        val count = recorder!!.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                        check(count > 0) { "录音输入已中断" }
                        filled += count
                    }
                    if (filled != pcm.size) continue
                    check(!clientSilenced) { "麦克风被系统静音或其他应用占用" }
                    check(repo.recordingsDir.usableSpace > 20 * 1024 * 1024) { "存储空间不足，请清理后继续" }
                    val prediction = classifier.classify(pcm)
                    val offset = ring.written
                    ring.append(pcm)
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
                session?.let { repo.dao.finishCapture(it.id, it.effectiveMs, end) }
                live.value = live.value.copy(state = SessionState.FINISHED, stopping = false)
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createRecorder(frame: Int): AudioRecord {
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        clientSilenced = false
        return AudioRecord.Builder().setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 8))
            .build().also { record ->
                check(record.state == AudioRecord.STATE_INITIALIZED) { "此设备暂不支持录音" }
                if (Build.VERSION.SDK_INT >= 29) record.registerAudioRecordingCallback(mainExecutor, object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        clientSilenced = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }?.isClientSilenced == true
                    }
                })
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
        live.value = live.value.copy(state = state, message = reason)
        persist()
    }

    private suspend fun persist() {
        val now = System.currentTimeMillis()
        session = session!!.copy(heartbeatAt = now)
        repo.dao.updateCapture(session!!.id, session!!.state, session!!.effectiveMs, now)
        lastPersist = SystemClock.elapsedRealtime()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
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

    private fun releaseRecorder() { recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null }

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
