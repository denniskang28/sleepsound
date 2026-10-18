package com.miansheng.sleep.domain

import kotlin.math.*

const val LEVEL_RATE = 16000
const val LEVEL_WINDOW = 15600
const val LEVEL_ALGORITHM = "a16k-bilinear-1k-v1"

data class LevelReading(val dbfs: Double?, val aDbfs: Double?, val status: String)

/** Streaming A-weighting approximation, limited to the 16 kHz capture bandwidth.
 * Bilinear transform of the analog A-weighting poles; normalized at 1 kHz.
 * This is not a claim of IEC sound level meter compliance.
 */
class SoundLevelMeter {
    private class Section(val p: Double, val q: Double, val highPass: Boolean) {
        var gain = 1.0
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = gain * (x + (if (highPass) -2 else 2) * x1 + x2) + (p + q) * y1 - p * q * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun magnitude(w: Double): Double {
            val middle = if (highPass) -2.0 else 2.0
            val nr = 1 + middle * cos(w) + cos(2*w)
            val ni = -middle * sin(w) - sin(2*w)
            val dr = 1 - (p+q)*cos(w) + p*q*cos(2*w)
            val di = (p+q)*sin(w) - p*q*sin(2*w)
            return sqrt((nr*nr+ni*ni)/(dr*dr+di*di))
        }
    }
    private fun pole(f: Double): Double = (2*LEVEL_RATE-2*PI*f)/(2*LEVEL_RATE+2*PI*f)
    private val sections = listOf(
        Section(pole(20.598997), pole(20.598997), true),
        Section(pole(107.65265), pole(737.86223), true),
        Section(pole(12194.217), pole(12194.217), false),
    ).also { stages -> stages.first().gain = 1 / stages.fold(1.0) { g, s -> g*s.magnitude(2*PI*1000/LEVEL_RATE) } }
    private var warmed = false

    /** First complete window is used for filter settling and is not reported. */
    fun accept(pcm: ShortArray): LevelReading? {
        require(pcm.size == LEVEL_WINDOW)
        var raw = 0.0; var weighted = 0.0; var clipped = false
        for (sample in pcm) {
            val x = sample / 32768.0
            val a = sections[2].process(sections[1].process(sections[0].process(x)))
            raw += x*x; weighted += a*a
            if (abs(sample.toInt()) >= 32760) clipped = true
        }
        if (!warmed) { warmed = true; return null }
        if (clipped) return LevelReading(null, null, "clipped")
        if (raw == 0.0 || weighted <= 0.0) return LevelReading(null, null, "floor")
        return LevelReading(10*log10(raw/pcm.size), 10*log10(weighted/pcm.size), "valid")
    }
}

data class WeightedLevel(val db: Double, val durationMs: Long)
data class LevelStats(val mean: Double, val maximum: Double, val p95: Double, val durationMs: Long)

/** Energy mean and duration-weighted ascending 95th percentile; never arithmetic-average dB. */
fun levelStats(values: List<WeightedLevel>): LevelStats? {
    val valid = values.filter { it.db.isFinite() && it.durationMs > 0 }
    if (valid.isEmpty()) return null
    val duration = valid.sumOf { it.durationMs }
    val max = valid.maxOf { it.db }
    val mean = max + 10*log10(valid.sumOf { 10.0.pow((it.db-max)/10)*it.durationMs } / duration)
    var elapsed = 0L
    val p95 = valid.sortedBy { it.db }.first { elapsed += it.durationMs; elapsed >= duration*.95 }.db
    return LevelStats(mean, max, p95, duration)
}

data class CalibrationPoint(val reference: Double, val measured: Double, val spread: Double, val durationMs: Long)
data class CalibrationFit(val offset: Double, val minimum: Double, val maximum: Double, val maxError: Double)

/** Product consistency checks, not instrument certification or an accuracy guarantee. */
fun fitCalibration(points: List<CalibrationPoint>): CalibrationFit {
    require(points.size >= 3) { "至少完成三组不同音量的测量" }
    require(points.all { it.reference.isFinite() && it.reference in 25.0..90.0 && it.measured.isFinite() }) { "参考值须为 25–90 dBA" }
    require(points.all { it.durationMs >= 30000 && it.spread.isFinite() && it.spread <= 3 }) { "声音不够稳定，请重新测量（P95−P5 不超过 3 dB）" }
    val references = points.map { it.reference }.sorted()
    require(references.last()-references.first() >= 15 && references.zipWithNext().all { (a,b) -> b-a >= 5 }) { "三组参考声级相邻至少差 5 dB，总跨度至少 15 dB" }
    val offsets = points.map { it.reference-it.measured }.sorted()
    val offset = if (offsets.size%2 == 0) (offsets[offsets.size/2-1]+offsets[offsets.size/2])/2 else offsets[offsets.size/2]
    val error = points.maxOf { abs(it.measured+offset-it.reference) }
    require(error <= 2) { "各组修正后最大偏差超过 2 dB，无法使用固定修正；请检查声源、位置及录音处理" }
    return CalibrationFit(offset, references.first(), references.last(), error)
}

fun levelRange(value: Double, min: Double, max: Double): Int = when { value < min -> -1; value > max -> 1; else -> 0 }

/** Bounded 0.1 dB histogram for live P95; persisted/report statistics use exact frames. */
class LiveLevelAccumulator {
    private var energy = 0.0
    private var duration = 0L
    private var maximum = Double.NEGATIVE_INFINITY
    private val histogram = java.util.TreeMap<Double, Long>()
    fun add(db: Double, ms: Long = 975) {
        energy += 10.0.pow(db/10)*ms; duration += ms; maximum = max(maximum, db)
        val bucket = (round(db*10)/10).coerceIn(-200.0, 20.0)
        histogram[bucket] = (histogram[bucket] ?: 0)+ms
    }
    fun stats(): LevelStats? {
        if (duration == 0L) return null
        var elapsed = 0L
        val p95 = histogram.entries.first { elapsed += it.value; elapsed >= duration*.95 }.key
        return LevelStats(10*log10(energy/duration), maximum, p95, duration)
    }
}
