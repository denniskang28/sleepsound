package com.miansheng.sleep.domain

import com.miansheng.sleep.data.*

class LevelSeries(val frames: List<SoundLevelFrame>, profiles: List<SoundCalibration>, relative: Boolean = false) {
    val valid = frames.filter { it.status == "valid" && it.dbfs?.isFinite() == true && it.aDbfs?.isFinite() == true }
    private val id = valid.map { it.calibrationId }.distinct().singleOrNull()
    val calibration = if (relative) null else profiles.find { it.id == id }
    val unit = if (calibration == null) "dBFS" else "估计 dBA"
    fun value(frame: SoundLevelFrame): Double? = if (frame.status != "valid" || frame.dbfs?.isFinite() != true || frame.aDbfs?.isFinite() != true) null else
        calibration?.let { frame.aDbfs!!+it.offset } ?: frame.dbfs
    fun number(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        calibration?.let {
            if (value < it.minimum) return "< %.1f".format(java.util.Locale.US, it.minimum)
            if (value > it.maximum) return "> %.1f".format(java.util.Locale.US, it.maximum)
        }
        return "%.1f".format(java.util.Locale.US, value)
    }
    val outsideRange: Boolean = calibration?.let { c -> valid.any { f -> levelRange(f.aDbfs!!+c.offset,c.minimum,c.maximum) != 0 } } ?: false
    val stats = levelStats(valid.map { WeightedLevel(calibration?.let { c -> it.aDbfs!!+c.offset } ?: it.dbfs!!, it.durationMs) })
}
