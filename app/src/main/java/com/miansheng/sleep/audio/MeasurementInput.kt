package com.miansheng.sleep.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import com.miansheng.sleep.domain.*

/** Shared by calibration and monitoring so calibration never uses a different recording path. */
class MeasurementInput(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
        MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val effects = mutableListOf<AudioEffect>()
    @Volatile private var silenced = false
    private val record: AudioRecord
    init {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
        record = AudioRecord.Builder().setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(LEVEL_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(LEVEL_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), LEVEL_WINDOW*8)).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "此设备暂不支持录音" }
            if (AutomaticGainControl.isAvailable()) runCatching {
                AutomaticGainControl.create(record.audioSessionId)?.let { effects += it; if (it.hasControl()) it.enabled = false }
            }
            if (NoiseSuppressor.isAvailable()) runCatching {
                NoiseSuppressor.create(record.audioSessionId)?.let { effects += it; if (it.hasControl()) it.enabled = false }
            }
            if (Build.VERSION.SDK_INT >= 29) record.registerAudioRecordingCallback(context.mainExecutor,
                object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        silenced = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }?.isClientSilenced == true
                    }
                })
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风暂不可用" }
        } catch (error: Exception) { close(); throw error }
    }
    val label: String get() = record.routedDevice?.let { "${Build.MODEL} · ${it.productName}" } ?: Build.MODEL
    fun configKey(): String? {
        val device = record.routedDevice ?: return null
        val effectState = effects.joinToString { "${it.descriptor.type}:${it.enabled}" }
        val config = if (Build.VERSION.SDK_INT >= 29) record.activeRecordingConfiguration?.let {
            "${it.clientAudioSource}/${it.format.sampleRate}/${it.format.encoding}/${it.format.channelMask}/" +
                it.clientEffects.joinToString { effect -> effect.uuid.toString() }
        } else "legacy"
        return "${Build.FINGERPRINT}|${device.type}|${device.address}|${device.productName}|$source|$LEVEL_RATE|mono16|$LEVEL_ALGORITHM|$effectState|$config"
    }
    fun read(pcm: ShortArray, keepReading: () -> Boolean = { true }): Boolean {
        var filled = 0
        while (filled < pcm.size && keepReading()) {
            val n = record.read(pcm, filled, pcm.size-filled, AudioRecord.READ_BLOCKING)
            check(n > 0) { "录音输入已中断" }
            filled += n
        }
        check(!silenced && (Build.VERSION.SDK_INT < 29 || record.activeRecordingConfiguration?.isClientSilenced != true)) {
            "麦克风被系统静音或其他应用占用"
        }
        return filled == pcm.size
    }
    override fun close() {
        effects.forEach { runCatching { it.release() } }; effects.clear()
        runCatching { record.stop() }; record.release()
    }
}
