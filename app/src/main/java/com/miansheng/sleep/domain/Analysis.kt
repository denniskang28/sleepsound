package com.miansheng.sleep.domain

import com.miansheng.sleep.data.*
import kotlin.math.roundToInt

data class NightStats(val snoreMs: Long, val talkCount: Int, val ratio: Double?)

fun nightStats(session: SleepSession, events: List<SoundEvent>): NightStats {
    val accepted = events.filter { it.sessionId == session.id && it.correction == null }
    val snore = accepted.filter { it.kind == "snore" }.sumOf { it.detectedMs }.coerceAtMost(session.effectiveMs)
    return NightStats(snore, accepted.count { it.kind == "talk" },
        if (session.insufficient || session.effectiveMs == 0L) null else snore.toDouble() / session.effectiveMs)
}

fun durationText(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60000
    return when {
        minutes >= 60 -> "${minutes / 60} 小时" + if (minutes % 60 > 0) " ${minutes % 60} 分" else ""
        minutes > 0 -> "$minutes 分钟"
        ms > 0 -> "不到 1 分钟"
        else -> "0 分钟"
    }
}

fun shortDuration(ms: Long): String = if (ms >= 60000) "${ms / 60000}分${ms / 1000 % 60}秒" else "${ms.coerceAtLeast(0) / 1000}秒"
fun ratioText(ratio: Double?): String = ratio?.let { "${(it * 100).roundToInt()}%" } ?: "数据不足"

data class Prediction(val kind: String?, val confidence: Float, val rms: Float)
data class Detection(val kind: String, val startSample: Long, val endSample: Long, val detectedSamples: Long, val confidence: Float, val rms: Float)

/** Operates on non-overlapping windows. Silent gaps merge bouts but never add to detected duration. */
class EventAssembler(
    private val sampleRate: Int = 16000,
    private val gapSeconds: Int = 3,
    private val maxSeconds: Int = 120,
    private val minWindows: Int = 2,
) {
    private var pending: Detection? = null
    private var hits = 0

    fun accept(prediction: Prediction, start: Long, count: Int): List<Detection> {
        val completed = mutableListOf<Detection>()
        val current = pending
        if (current != null && (prediction.kind != null && prediction.kind != current.kind ||
                    start - current.endSample >= gapSeconds * sampleRate ||
                    start + count - current.startSample > maxSeconds * sampleRate)) {
            flush()?.let(completed::add)
        }
        if (prediction.kind != null) {
            pending = pending?.let { it.copy(endSample = start + count,
                detectedSamples = it.detectedSamples + count,
                confidence = maxOf(it.confidence, prediction.confidence), rms = maxOf(it.rms, prediction.rms)) }
                ?: Detection(prediction.kind, start, start + count, count.toLong(), prediction.confidence, prediction.rms)
            hits++
        }
        return completed
    }

    fun flush(): Detection? {
        val result = pending?.takeIf { hits >= minWindows }
        pending = null; hits = 0
        return result
    }
}

/** Fixed-size memory buffer; never stores an entire night. */
class AudioRing(private val capacity: Int) {
    private val samples = ShortArray(capacity)
    var written: Long = 0; private set
    fun append(data: ShortArray, count: Int = data.size) {
        repeat(count) { samples[((written + it) % capacity).toInt()] = data[it] }
        written += count
    }
    fun slice(start: Long, end: Long): ShortArray {
        require(start >= maxOf(0, written - capacity) && end <= written && end >= start) { "Audio has left the ring buffer" }
        return ShortArray((end - start).toInt()) { samples[((start + it) % capacity).toInt()] }
    }
}
