package com.miansheng.sleep.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miansheng.sleep.audio.LiveMonitor
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import kotlin.math.*

@Composable
fun LiveSoundLevels(live: LiveMonitor) {
    val c = live.calibration
    val reading = live.currentLevel
    val number = if (live.state != SessionState.RUNNING) "—" else when (reading?.status) {
        "clipped" -> "输入过载"
        "floor" -> "无有效信号"
        "valid" -> displayLevel(if (c != null) reading.aDbfs!!+c.offset else reading.dbfs, c)
        else -> "—"
    }
    val unit = if (c != null) "估计 dBA" else "dBFS"
    SleepCard(color = SleepColors.Card.copy(alpha = .55f), padding = 16) {
        Txt("当前声音强度 · $unit", 12, SleepColors.Secondary)
        Txt(number, 32, SleepColors.PrimaryText, FontWeight.Medium)
        val stats = live.levels
        // Live aggregates cannot establish in-range coverage; show relative statistics until the report.
        if (stats != null && c == null) LevelMetricRow(stats, unit)
        Txt(live.levelMessage, 11, SleepColors.Muted)
        if (c != null) Txt("验证范围 ${c.minimum.toInt()}–${c.maximum.toInt()} dBA · 汇总见报告", 11, SleepColors.Muted)
    }
}

fun displayLevel(value: Double?, calibration: SoundCalibration?): String {
    if (value == null || !value.isFinite()) return "—"
    if (calibration != null) {
        if (value < calibration.minimum) return "< %.1f".format(java.util.Locale.US, calibration.minimum)
        if (value > calibration.maximum) return "> %.1f".format(java.util.Locale.US, calibration.maximum)
    }
    return "%.1f".format(java.util.Locale.US, value)
}

@Composable
private fun LevelMetricRow(stats: LevelStats?, unit: String, c: SoundCalibration? = null, hideAggregates: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("能量平均" to stats?.mean.takeUnless { hideAggregates }, "最高短时" to stats?.maximum,
            "P95" to stats?.p95.takeUnless { hideAggregates }).forEach { (title, value) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(title, 11, SleepColors.Secondary)
                Txt(displayLevel(value, c), 20, SleepColors.Text, FontWeight.Medium)
                Txt(unit, 10, SleepColors.Muted)
            }
        }
    }
}

@Composable
fun SoundLevelReport(session: SleepSession, data: AppData) {
    val frames = remember(data.levels, session.id) { data.levels.filter { it.sessionId == session.id } }
    var relative by remember(session.id) { mutableStateOf(false) }
    var explain by remember(session.id) { mutableStateOf(false) }
    val base = remember(frames, data.calibrations) { LevelSeries(frames, data.calibrations) }
    val series = remember(base, relative) { if (relative) LevelSeries(frames, data.calibrations, true) else base }
    SleepCard {
        Txt("夜间声音强度", 16, weight = FontWeight.SemiBold)
        if (frames.isEmpty()) {
            Txt("这次记录没有连续声音强度数据。升级后的新监测会自动保存，历史录音不会补算为整晚统计。", 13, SleepColors.Secondary)
        } else {
            if (base.calibration != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("估计 dBA", !relative, { relative = false })
                Choice("相对 dBFS", relative, { relative = true })
            }
            LevelMetricRow(series.stats, series.unit, series.calibration, series.outsideRange)
            val c = series.calibration
            Txt(if (c == null) "未校准或包含不同配置时段 · dBFS 是相对录音电平" else
                "验证范围 ${c.minimum.toInt()}–${c.maximum.toInt()} dBA · 校准于 ${java.time.Instant.ofEpochMilli(c.createdAt).atZone(java.time.ZoneId.of(session.zoneId)).toLocalDate()}", 11, SleepColors.Muted)
            if (series.outsideRange) Hint("部分时段超出验证范围，平均与 P95 暂不报告；灰色标记表示范围外读数，可切换 dBFS 查看相对统计。")
            if (frames.any { it.status == "clipped" }) Hint("存在输入过载时段，真实最高声级可能更高；以下统计仅含有效窗口。", "triangle-alert", SleepColors.Danger)
            LevelPlot(series, session.startedAt, session.endedAt ?: session.heartbeatAt, session.zoneId)
            Txt("有效声级 ${durationText(series.stats?.durationMs ?: 0)} / 采音 ${durationText(session.effectiveMs)}；无信号、过载、暂停、中断及滤波预热不计入声级统计。", 11, SleepColors.Muted)
            TextButton(onClick={explain=!explain}) { Txt(if(explain)"收起统计说明" else "统计口径与局限",12,SleepColors.PrimaryText) }
            if(explain) {
                Txt("平均按声能计算；最高为约 0.975 秒窗口最大值，非瞬时峰值。P95 表示 95% 有效时长的窗口电平不超过此值，不是声学 L95。曲线竖线为时间桶最小–最大值，圆点为能量平均。", 11, SleepColors.Muted)
                if (c != null) Txt("手机位置处的混合声音估计 · 16 kHz 采音、8 kHz 以下限带 A 计权；单一修正不能消除频响误差，不替代专业声级计。", 11, SleepColors.Muted)
            }
        }
    }
}

@Composable
fun EventSoundLevels(event: SoundEvent, data: AppData) {
    val frames = remember(data.levels, event.id) { data.levels.filter { it.sessionId == event.sessionId &&
        it.startedAt < event.endedAt && it.startedAt+it.durationMs > event.startedAt }.map { frame ->
            val start = max(frame.startedAt, event.startedAt)
            frame.copy(startedAt = start, durationMs = min(frame.startedAt+frame.durationMs,event.endedAt)-start)
        } }
    if (frames.isNotEmpty()) {
        val series = remember(frames, data.calibrations) { LevelSeries(frames, data.calibrations) }
        Txt("事件时段声音 · ${series.unit}", 12, SleepColors.Secondary)
        LevelMetricRow(series.stats, series.unit, series.calibration, series.outsideRange)
        if (series.outsideRange) Txt("含范围外时段，平均与 P95 暂不报告", 11, SleepColors.Muted)
        LevelPlot(series, event.startedAt, event.endedAt, data.sessions.find { it.id == event.sessionId }?.zoneId ?: java.time.ZoneId.systemDefault().id)
        Txt("包含事件期间环境声音，不是分离后的鼾声声压。", 11, SleepColors.Muted)
    } else Txt("此片段没有连续声级数据", 11, SleepColors.Muted)
}

private data class LevelBucket(val time: Long, val min: Double, val max: Double, val mean: Double)

@Composable
fun LevelPlot(series: LevelSeries, start: Long, end: Long, zone: String) {
    val duration = (end-start).coerceAtLeast(1)
    val buckets = remember(series, start, end) {
        series.valid.groupBy { ((it.startedAt-start)*240/duration).coerceIn(0,239) }.values.map { frames ->
            val values = frames.map { WeightedLevel(series.value(it)!!, it.durationMs) }
            LevelBucket(frames.first().startedAt, values.minOf { it.db }, values.maxOf { it.db }, levelStats(values)!!.mean)
        }
    }
    val c = series.calibration
    val lower = c?.minimum ?: (floor(min(buckets.minOfOrNull { it.min } ?: -60.0, -60.0)/10)*10)
    val upper = c?.maximum ?: (ceil(max(buckets.maxOfOrNull { it.max } ?: -20.0, -20.0)/10)*10)
    var selected by remember(series, start) { mutableStateOf<SoundLevelFrame?>(null) }
    Txt("${series.unit} · 点击曲线查看", 11, SleepColors.Muted)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(40.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(upper, (upper+lower)/2, lower).forEach { Txt("%.0f".format(java.util.Locale.US,it), 10, SleepColors.Muted) }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "声音强度曲线，单位 ${series.unit}，空缺表示无有效声级数据" }
            .pointerInput(series, start, end) { detectTapGestures { pos ->
                val time = start+(pos.x/size.width*duration).toLong()
                selected = series.frames.firstOrNull { time >= it.startedAt && time < it.startedAt+it.durationMs }
            } }) {
            fun y(value: Double) = (1-(value-lower)/(upper-lower)).toFloat().coerceIn(0f,1f)*size.height
            listOf(0f,.5f,1f).forEach { drawLine(SleepColors.Border, Offset(0f,it*size.height),Offset(size.width,it*size.height),1.dp.toPx()) }
            buckets.forEach { b ->
                val x = ((b.time-start).toDouble()/duration*size.width).toFloat().coerceIn(0f,size.width)
                val color = if (c != null && (b.min < lower || b.max > upper)) SleepColors.Muted else SleepColors.Primary
                drawLine(color.copy(alpha=.65f),Offset(x,y(b.min)),Offset(x,y(b.max)),2.dp.toPx())
                drawCircle(color,1.5.dp.toPx(),Offset(x,y(b.mean)))
            }
            selected?.let { val x = ((it.startedAt-start).toDouble()/duration*size.width).toFloat().coerceIn(0f,size.width)
                drawLine(SleepColors.Text,Offset(x,0f),Offset(x,size.height),1.dp.toPx()) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start=46.dp), horizontalArrangement=Arrangement.SpaceBetween) {
        listOf(start,start+duration/2,end).forEach { Txt(clock(it,zone),10,SleepColors.Muted) }
    }
    selected?.let { f -> Txt("${clock(f.startedAt,zone)} · ${if (f.status == "valid") series.number(series.value(f))+" "+series.unit else if (f.status == "clipped") "输入过载" else "无有效信号"}",12,SleepColors.PrimaryText) }
}
