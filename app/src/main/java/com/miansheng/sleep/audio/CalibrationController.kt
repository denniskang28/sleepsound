package com.miansheng.sleep.audio

import com.miansheng.sleep.SleepApplication
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

data class CalibrationCapture(val measured: Double, val spread: Double, val durationMs: Long,
    val configKey: String, val deviceLabel: String)
data class CalibrationState(
    val capturing: Boolean = false, val saving: Boolean = false, val capturedMs: Long = 0,
    val currentDbfs: Double? = null, val pending: CalibrationCapture? = null,
    val points: List<CalibrationPoint> = emptyList(), val configKey: String? = null,
    val deviceLabel: String = "", val error: String? = null, val saved: Boolean = false,
)

class CalibrationController(private val app: SleepApplication, private val scope: CoroutineScope) {
    val state = MutableStateFlow(CalibrationState())
    private var job: Job? = null
    fun reset() { cancel(); state.value = CalibrationState() }
    fun cancel() {
        job?.cancel(); job = null
        state.value = state.value.copy(capturing = false, saving = false, capturedMs = 0, currentDbfs = null)
    }
    fun capture() {
        if (state.value.capturing || state.value.saving) return
        if (MonitorService.live.value.state != SessionState.FINISHED) {
            state.value = state.value.copy(error = "请先结束夜间监测，再进行校准"); return
        }
        state.value = state.value.copy(capturing = true, capturedMs = 0, pending = null, error = null, saved = false)
        job = scope.launch {
            try {
                val captured = withContext(Dispatchers.IO) {
                    app.ready.await()
                    MeasurementInput(app).use { input ->
                        val meter = SoundLevelMeter()
                        val pcm = ShortArray(LEVEL_WINDOW)
                        val values = mutableListOf<WeightedLevel>()
                        var config: String? = null
                        var duration = 0L
                        while (duration < 30000) {
                            ensureActive()
                            check(input.read(pcm) { isActive }) { "测量已停止" }
                            ensureActive()
                            val key = input.configKey() ?: error("暂时无法识别麦克风，请重试")
                            if (config == null) config = key
                            check(key == config) { "测量期间麦克风或录音配置改变，请重新采集" }
                            val reading = meter.accept(pcm) ?: continue
                            check(reading.status == "valid") { if (reading.status == "clipped") "输入过载，请调低音箱音量后重测" else "输入无有效信号，请检查麦克风后重测" }
                            values += WeightedLevel(reading.aDbfs!!, 975)
                            duration += 975
                            state.value = state.value.copy(capturedMs = duration, currentDbfs = reading.aDbfs)
                        }
                        val sorted = values.map { it.db }.sorted()
                        val spread = sorted[(ceil(sorted.size*.95).toInt()-1).coerceAtLeast(0)] - sorted[(ceil(sorted.size*.05).toInt()-1).coerceAtLeast(0)]
                        check(spread <= 3) { "声音变化过大（P95−P5 为 %.1f dB），请使用稳定声源重测".format(spread) }
                        CalibrationCapture(levelStats(values)!!.mean, spread, duration, config!!, input.label)
                    }
                }
                state.value = state.value.copy(capturing = false, pending = captured, currentDbfs = null)
            } catch (error: Exception) {
                if (error !is CancellationException) state.value = state.value.copy(capturing = false, error = error.message ?: "采集失败，请重试")
            }
        }
    }
    fun addReference(text: String) {
        val capture = state.value.pending ?: return
        val value = text.trim().toDoubleOrNull()
        if (value == null || !value.isFinite() || value !in 25.0..90.0) {
            state.value = state.value.copy(error = "请输入参考仪器的 LAeq，范围 25–90 dBA"); return
        }
        if (state.value.configKey != null && state.value.configKey != capture.configKey) {
            state.value = state.value.copy(error = "录音配置已改变，请清空本轮测量后重新开始"); return
        }
        state.value = state.value.copy(points = state.value.points + CalibrationPoint(value, capture.measured, capture.spread, capture.durationMs),
            pending = null, configKey = capture.configKey, deviceLabel = capture.deviceLabel, error = null)
    }
    fun removePoint(index: Int) {
        if (!state.value.capturing && !state.value.saving) state.value = state.value.copy(points = state.value.points.filterIndexed { i, _ -> i != index }, error = null)
    }
    fun save(referenceMeter: String, done: () -> Unit) {
        val snapshot = state.value
        if (snapshot.capturing || snapshot.saving || snapshot.pending != null || snapshot.saved) return
        try {
            require(referenceMeter.isNotBlank()) { "请填写参考声级计型号或编号，便于复核" }
            val fit = fitCalibration(snapshot.points)
            val config = requireNotNull(snapshot.configKey)
            val points = JSONArray().apply { snapshot.points.forEach { p -> put(JSONObject().put("reference", p.reference)
                .put("measured", p.measured).put("spread", p.spread).put("durationMs", p.durationMs)) } }
            state.value = state.value.copy(saving = true)
            job = scope.launch {
                try {
                    withContext(Dispatchers.IO) { app.repository.saveCalibration(SoundCalibration(configKey = config,
                        deviceLabel = snapshot.deviceLabel, referenceMeter = referenceMeter.trim().take(100),
                        offset = fit.offset, minimum = fit.minimum, maximum = fit.maximum, maxError = fit.maxError, pointsJson = points.toString())) }
                    state.value = state.value.copy(saved = true, saving = false, error = null)
                    done()
                } catch (error: Exception) {
                    if (error !is CancellationException) state.value = state.value.copy(saving = false, error = error.message ?: "校准未保存，请重试")
                }
            }
        } catch (error: IllegalArgumentException) { state.value = state.value.copy(error = error.message) }
    }
}
