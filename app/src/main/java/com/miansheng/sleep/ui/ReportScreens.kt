@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.miansheng.sleep.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import kotlin.math.*

@Composable
fun ReportScreen(session: SleepSession, data: AppData, vm: SleepViewModel) {
    val events = data.events.filter { it.sessionId == session.id }
    val segments = data.segments.filter { it.sessionId == session.id }
    val stats = nightStats(session, events)
    val paused = segments.filter { it.kind == "pause" }.sumOf { (it.endedAt ?: session.endedAt ?: it.startedAt) - it.startedAt }
    val gaps = segments.filter { it.kind == "gap" }.sumOf { (it.endedAt ?: session.endedAt ?: it.startedAt) - it.startedAt }
    val duration = ((session.endedAt ?: session.heartbeatAt) - session.startedAt).coerceAtLeast(1)
    Column(Modifier.fillMaxSize()) {
        PageTitle("监测报告", vm::back, trailing = { IconTap("ellipsis", "报告更多操作") { vm.sheet = "sessionMore" } })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(nightTitle(session), 26, weight = FontWeight.SemiBold)
                Txt("${sessionRange(session)} · 会话 ${durationText(duration)}", 14, SleepColors.Secondary)
            }
            if (session.insufficient) SleepCard(color = Color(0xFF222E3F), padding = 14) {
                Hint(if (session.effectiveMs == 0L) "本次没有有效录音数据，无法生成完整统计。" else "数据不足：有效监测少于 3 小时，鼾声占比不计算，也不计入趋势平均。")
            }
            if (session.recovered) Hint("上次监测意外中断，已保留中断前的可用记录。", "triangle-alert", SleepColors.Danger)
            Txt("有效监测 ${durationText(session.effectiveMs)}。" +
                if (stats.snoreMs == 0L && stats.talkCount == 0) "未检测到明显鼾声，也未检测到疑似梦话。"
                else "检测到鼾声 ${durationText(stats.snoreMs)}，另有 ${stats.talkCount} 段疑似梦话。", 15, Color(0xFFD8D2C8))
            SleepCard {
                Txt("有效监测时长", 13, SleepColors.Secondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt("${session.effectiveMs / 3600000}", 44, weight = FontWeight.SemiBold)
                    Txt("小时", 15, SleepColors.Secondary, modifier = Modifier.padding(start = 4.dp, end = 8.dp, bottom = 10.dp))
                    Txt("${session.effectiveMs / 60000 % 60}".padStart(2, '0'), 44, weight = FontWeight.SemiBold)
                    Txt("分", 15, SleepColors.Secondary, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                }
                Canvas(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(SleepColors.Elevated)) {
                    val effective = (session.effectiveMs.toFloat() / duration).coerceIn(0f, 1f)
                    val pause = (paused.toFloat() / duration).coerceIn(0f, 1-effective)
                    val gap = (gaps.toFloat() / duration).coerceIn(0f, 1-effective-pause)
                    drawRect(SleepColors.Primary, size = Size(size.width * effective, size.height))
                    drawRect(SleepColors.Muted, Offset(size.width*effective,0f), Size(size.width*pause,size.height))
                    drawRect(SleepColors.Danger, Offset(size.width*(effective+pause),0f), Size(size.width*gap,size.height))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend("有效 ${durationText(session.effectiveMs)}", SleepColors.Primary)
                    if (paused > 0) Legend("暂停 ${durationText(paused)}", SleepColors.Muted)
                    if (gaps > 0) Legend("录音中断 ${durationText(gaps)}", SleepColors.Danger)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SleepCard(Modifier.weight(1f), padding = 18) {
                    Legend("鼾声累计", SleepColors.Snore)
                    if (stats.snoreMs > 0) {
                        Metric(if (stats.snoreMs < 60000) "${stats.snoreMs / 1000}" else "${stats.snoreMs / 60000}", if (stats.snoreMs < 60000) "秒" else "分钟", SleepColors.SnoreText)
                        Txt("占比 ${ratioText(stats.ratio)}", 13, SleepColors.Secondary)
                    } else Txt("未检测到明显鼾声", 16, weight = FontWeight.Medium)
                }
                SleepCard(Modifier.weight(1f), padding = 18) {
                    Legend("疑似梦话", SleepColors.Talk, diamond = true)
                    if (stats.talkCount > 0) { Metric("${stats.talkCount}", "段", SleepColors.TalkText); Txt("可在下方回听", 13, SleepColors.Muted) }
                    else Txt("未检测到疑似梦话", 16, weight = FontWeight.Medium)
                }
            }
            SleepCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Txt("夜间声音时间轴", 16, weight = FontWeight.SemiBold); Txt("点击事件查看", 12, SleepColors.Muted) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend("鼾声", SleepColors.Snore); Legend("疑似梦话", SleepColors.Talk, true); Legend("暂停", SleepColors.Muted); Legend("中断", SleepColors.Danger)
                }
                Timeline(session, events, segments, vm.selectedTimelineId) { vm.selectedTimelineId = it }
                events.find { it.id == vm.selectedTimelineId }?.let { event ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SleepColors.Elevated).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("${event.label} · ${clock(event.startedAt, session.zoneId)}", 14, weight = FontWeight.Medium)
                            Txt("${shortDuration(event.detectedMs)} · 相对强度 ${event.strength}", 12, SleepColors.Secondary)
                            if (!event.available) Txt(clipMessage(event), 12, SleepColors.Muted)
                        }
                        if (event.available) IconTap("play", "播放选中的录音", SleepColors.Primary, SleepColors.Primary.copy(alpha = .12f)) { vm.openPlayer(event, true) }
                    }
                }
                segments.find { it.id == vm.selectedTimelineId }?.let { segment ->
                    Hint("${if (segment.kind == "pause") "暂停" else "录音中断"} · ${clock(segment.startedAt, session.zoneId)}–${clock(segment.endedAt ?: segment.startedAt, session.zoneId)}\n${segment.reason}", color = if (segment.kind == "gap") SleepColors.Danger else SleepColors.Secondary)
                }
            }
            Txt("值得回听", 16, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            val representative = listOfNotNull(
                events.filter { it.kind == "talk" && it.correction == null && it.available }.maxByOrNull { it.detectedMs },
                events.filter { it.kind == "snore" && it.correction == null && it.available }.maxByOrNull { it.detectedMs })
            representative.forEach { event -> RecordingCard(event, session, vm, representative = true) }
            if (events.isNotEmpty()) {
                if (representative.isEmpty()) SleepCard(color = Color(0xFF161F2C), padding = 18) {
                    Hint(if (events.none { it.available }) "录音已清理或不可用，统计数据与时间轴仍然保留。" else "没有推荐片段，可以查看全部录音。", "archive")
                }
                ActionButton("查看全部录音 · ${events.size} 段", "chevron-right", primary = false) { vm.filter = "all"; vm.screen = Screen.RECORDINGS }
            } else SleepCard(color = Color(0xFF161F2C), padding = 18) { Hint("没有需要回听的片段", "moon-star") }
            SleepCard(modifier = Modifier.padding(top = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Txt("今早感觉如何", 16, weight = FontWeight.SemiBold); Txt("可选", 12, SleepColors.Muted) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("精神较好", "一般", "比较疲惫").forEach { feeling -> Choice(feeling, session.feeling == feeling, { vm.task { vm.repo.feel(session.id, feeling.takeUnless { session.feeling == feeling }) } }) }
                }
                Txt("生活标签", 16, weight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.tags.forEach { Tag(it, modifier = Modifier.padding(vertical = 4.dp)) }
                    Choice(if (session.tags.isEmpty()) "+ 添加标签" else "+ 编辑标签", false, {
                        vm.draftTags = session.tags; vm.draftNote = session.note; vm.sheet = "tags"
                    })
                }
                if (session.note.isNotBlank()) Txt(session.note, 14, SleepColors.Secondary)
                Txt("不需要每天填写，标签会出现在趋势里帮助回顾。", 12, SleepColors.Muted)
            }
            Txt("基于手机麦克风的声音识别，仅供自我观察，不能用于医疗诊断。声音强弱为相对等级；同床时，部分声音可能来自他人。", 12, SleepColors.Muted, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
fun Legend(label: String, color: Color, diamond: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) {
            if (diamond) drawPath(Path().apply { moveTo(size.width/2,0f); lineTo(size.width,size.height/2); lineTo(size.width/2,size.height); lineTo(0f,size.height/2); close() }, color)
            else drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()))
        }
        Txt(label, 12, SleepColors.Secondary)
    }
}

@Composable
fun Metric(number: String, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) { Txt(number, 36, color, FontWeight.SemiBold); Txt(unit, 14, SleepColors.Secondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
}

@Composable
fun Timeline(session: SleepSession, events: List<SoundEvent>, segments: List<CaptureSegment>, selected: String?, onSelect: (String) -> Unit) {
    val length = ((session.endedAt ?: session.heartbeatAt) - session.startedAt).coerceAtLeast(1)
    fun x(time: Long) = ((time - session.startedAt).toFloat() / length).coerceIn(0f,1f)
    val markers = events.map { Triple(it.id, x(it.startedAt), it.kind) } + segments.filter { it.kind != "active" }.map { Triple(it.id, x(it.startedAt), it.kind) }
    Canvas(Modifier.fillMaxWidth().height(96.dp)
        .semantics { contentDescription = "夜间声音时间轴，${events.size} 个声音事件，${segments.count { it.kind != "active" }} 段暂停或中断" }
        .pointerInput(markers) { detectTapGestures { pos ->
            markers.minByOrNull { abs(it.second * size.width - pos.x) + if (it.third == "talk" && pos.y > size.height * .3) size.height else 0 }
                ?.let { onSelect(it.first) }
        } }) {
        val top = 30.dp.toPx(); val h = 44.dp.toPx()
        drawRoundRect(Color(0xFF151E2B), Offset(0f,top),Size(size.width,h), CornerRadius(8.dp.toPx()))
        events.forEach { event ->
            val color = if (event.correction != null) SleepColors.Muted else if (event.kind == "snore") SleepColors.Snore else SleepColors.Talk
            val left = x(event.startedAt)*size.width
            if (event.kind == "talk") {
                val r = 5.dp.toPx(); val cy = top-12.dp.toPx()
                drawPath(Path().apply { moveTo(left,cy-r); lineTo(left+r,cy); lineTo(left,cy+r); lineTo(left-r,cy); close() }, color)
                if (event.id == selected) drawCircle(SleepColors.Text, 9.dp.toPx(), Offset(left,cy),style=Stroke(1.5.dp.toPx()))
            } else {
                val width = max(4.dp.toPx(),(x(event.endedAt)-x(event.startedAt))*size.width).coerceAtMost(size.width-left)
                val eh = when(event.strength) { "强" -> h*.85f; "中" -> h*.6f; else -> h*.35f }
                drawRoundRect(color,Offset(left,top+(h-eh)/2),Size(width,eh),CornerRadius(2.dp.toPx()))
                if (event.id == selected) drawRoundRect(SleepColors.Text,Offset(left,top+(h-eh)/2),Size(width,eh),CornerRadius(2.dp.toPx()),style=Stroke(1.5.dp.toPx()))
            }
        }
        segments.filter { it.kind != "active" }.forEach { gap ->
            val left = x(gap.startedAt)*size.width
            val width = max(3.dp.toPx(), (x(gap.endedAt ?: session.heartbeatAt)-x(gap.startedAt))*size.width).coerceAtMost(size.width-left)
            if (gap.kind == "gap") drawRoundRect(SleepColors.Danger,Offset(left,top),Size(width,h),CornerRadius(2.dp.toPx()),style=Stroke(1.5.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
            else { drawRect(SleepColors.Border,Offset(left,top),Size(width,h)); var stripe=left; while (stripe<left+width) { drawLine(SleepColors.Muted,Offset(stripe,top),Offset(stripe,top+h),1.dp.toPx()); stripe+=4.dp.toPx() } }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(session.startedAt, session.startedAt+length/3, session.startedAt+length*2/3, session.startedAt+length).forEach { Txt(clock(it,session.zoneId), 11, SleepColors.Muted) }
    }
}

@Composable
fun RecordingCard(event: SoundEvent, session: SleepSession, vm: SleepViewModel, representative: Boolean = false) {
    val color = if (event.correction != null) SleepColors.Muted else if (event.kind == "talk") SleepColors.Talk else SleepColors.Snore
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (vm.playerEventId == event.id) Color(0xFF1F2B3C) else SleepColors.Card)
        .clickable { vm.openPlayer(event) }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (representative) 14.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(if (vm.playerEventId == event.id && vm.playing) "pause" else "play", "播放 ${event.label} ${clock(event.startedAt,session.zoneId)}", if (event.available) color else SleepColors.Muted, color.copy(alpha = .15f)) { vm.openPlayer(event,true) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (representative) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (event.kind == "talk") "一段疑似梦话" else "较长的一段鼾声", 15, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                    Txt(clock(event.startedAt,session.zoneId), 13, SleepColors.Secondary)
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(clock(event.startedAt,session.zoneId), 16, weight = FontWeight.SemiBold)
                Tag(event.label, color, modifier = Modifier.weight(1f, fill = false))
            }
            if (event.available) {
                Waveform(event.waveform, color, Modifier.fillMaxWidth().height(22.dp))
                Txt("检测 ${shortDuration(event.detectedMs)}", 11, SleepColors.Secondary)
            } else Txt(clipMessage(event), 12, SleepColors.Muted)
        }
        if (!representative) IconTap("star", if (event.favorite) "取消收藏" else "收藏录音", if (event.favorite) SleepColors.SnoreText else SleepColors.Muted) { vm.task { vm.repo.favorite(event.id) } }
    }
}

@Composable
fun Waveform(values: List<Float>, color: Color, modifier: Modifier = Modifier, progress: Float? = null, seek: ((Float) -> Unit)? = null) {
    val currentSeek by rememberUpdatedState(seek)
    val controls = if (seek != null) Modifier
        .semantics {
            contentDescription = "录音播放进度"
            progressBarRangeInfo = ProgressBarRangeInfo((progress ?: 0f).coerceIn(0f,1f), 0f..1f)
            setProgress { currentSeek?.invoke(it.coerceIn(0f,1f)); true }
        }
        .pointerInput(Unit) { detectTapGestures { currentSeek?.invoke((it.x/size.width).coerceIn(0f,1f)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures(
            onDragStart = { currentSeek?.invoke((it.x/size.width).coerceIn(0f,1f)) },
            onHorizontalDrag = { change, _ -> change.consume(); currentSeek?.invoke((change.position.x/size.width).coerceIn(0f,1f)) }) }
        else Modifier
    Canvas(modifier.then(controls)) {
        val bars = if (values.isEmpty()) listOf(0f) else values
        val step = size.width / bars.size
        bars.forEachIndexed { index, value ->
            val h = max(2.dp.toPx(),value.coerceIn(0f,1f)*size.height)
            val c = if (progress == null || index.toFloat()/bars.size <= progress) color else Color(0xFF3A4658)
            drawRoundRect(c,Offset(index*step,(size.height-h)/2),Size(max(1f,step-2.dp.toPx()),h),CornerRadius(1.dp.toPx()))
        }
        progress?.let { drawLine(SleepColors.Text,Offset(size.width*it,0f),Offset(size.width*it,size.height),2.dp.toPx()) }
    }
}

@Composable
fun RecordingsScreen(session: SleepSession, data: AppData, vm: SleepViewModel) {
    val events = data.events.filter { it.sessionId == session.id }
    val filtered = events.filter { when(vm.filter) { "fav" -> it.favorite; "snore", "talk" -> it.kind == vm.filter && it.correction == null; else -> true } }
    Column(Modifier.fillMaxSize()) {
        PageTitle("全部录音", vm::back, "${session.date.monthValue}月${session.date.dayOfMonth}日 夜间 · ${events.size} 段")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "snore" to "鼾声", "talk" to "疑似梦话", "fav" to "收藏").forEach { (key,label) ->
                val count = events.count { when(key) { "fav" -> it.favorite; "all" -> true; else -> it.kind == key && it.correction == null } }
                Choice("$label $count", vm.filter == key, { vm.filter = key })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            filtered.forEach { RecordingCard(it,session,vm) }
            if (filtered.isEmpty()) EmptyState(if (vm.filter == "fav") "还没有收藏的录音" else "没有符合筛选的录音", "你可以切换筛选，或在录音旁点击星标收藏。", if (vm.filter == "fav") "star" else "headphones")
        }
    }
}
