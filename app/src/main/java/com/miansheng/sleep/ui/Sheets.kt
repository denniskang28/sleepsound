@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.miansheng.sleep.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun SheetContents(vm: SleepViewModel, data: AppData, session: SleepSession?, player: SoundEvent?, retention: Int,
                  requestPermissions: () -> Unit, share: (SoundEvent) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        when (vm.sheet) {
            "permission" -> {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(SleepColors.Primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Glyph("mic", SleepColors.Primary, 26) }
                Txt("需要使用麦克风", 22, weight = FontWeight.SemiBold)
                Txt("用于在夜间识别鼾声和疑似梦话。", 14, SleepColors.Secondary)
                listOf("只在你开始监测后录音，结束即停止", "识别在本机完成，录音默认保存在本机", "可以随时在设置中删除录音").forEach { Hint(it, "check", Color(0xFFD8D2C8)) }
                ActionButton("继续并授权", onClick = requestPermissions)
                TextButton(onClick = { vm.sheet = null }) { Txt("稍后", 15, SleepColors.Secondary) }
            }
            "end" -> {
                Txt("结束今晚的监测？", 22, weight = FontWeight.SemiBold)
                Txt("已有效监测 ${durationText(data.live.effectiveMs)}。结束后会立即生成报告。", 14, SleepColors.Secondary)
                ActionButton("结束并查看报告", onClick = vm::finishMonitoring)
                ActionButton("继续监测", "moon", primary = false) { vm.sheet = null }
            }
            "tags" -> if (session != null) {
                Txt("添加生活标签", 20, weight = FontWeight.SemiBold)
                Txt("${nightTitle(session)} · 可多选", 13, SleepColors.Secondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("饮酒", "鼻塞", "侧睡", "熬夜", "运动").forEach { tag ->
                        Choice(tag, tag in vm.draftTags, { vm.draftTags = if (tag in vm.draftTags) vm.draftTags - tag else vm.draftTags + tag })
                    }
                }
                Txt("自定义备注", 13, SleepColors.Secondary)
                OutlinedTextField(vm.draftNote, { vm.draftNote = it.take(200) }, Modifier.fillMaxWidth(),
                    placeholder = { Txt("例如：晚上喝了咖啡", 15, SleepColors.Muted) },
                    shape = RoundedCornerShape(14.dp), maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(color = SleepColors.Text, fontFamily = Chinese),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = SleepColors.Border, focusedBorderColor = SleepColors.Primary))
                ActionButton("完成", "check") { vm.saveTags(session) }
            }
            "correct" -> if (player != null) {
                Txt("这段声音是？", 20, weight = FontWeight.SemiBold)
                Txt("${player.label} · ${clock(player.startedAt)}。修正后报告统计会同步更新。", 13, SleepColors.Secondary)
                val choices = listOf(if (player.kind == "snore") "不是鼾声" else "不是说话声", "普通说话", "电视或音乐", "其他人的声音", "其他声音")
                Column { choices.forEach { choice -> RadioRow(choice, vm.correctionChoice == choice) { vm.correctionChoice = choice } } }
                ActionButton("确认修正", "check", enabled = vm.correctionChoice != null) { vm.correct(player) }
                if (player.correction != null) TextButton(onClick = { vm.task { vm.repo.correct(player.id, null) }; vm.sheet = null; vm.snack("已恢复原识别结果") }) { Txt("恢复原识别结果", 14, SleepColors.Primary) }
            }
            "more" -> if (player != null) {
                Txt("录音操作", 20, weight = FontWeight.SemiBold)
                SettingRow("识别有误", "修正声音类型", "audio-lines") { vm.correctionChoice = null; vm.sheet = "correct" }
                SettingRow("分享这段录音", "主动选择分享应用", "share-2") { share(player); vm.sheet = null }
                SettingRow("删除录音", "报告统计与时间轴保留", "trash-2", true) { vm.sheet = "deleteClip" }
            }
            "sessionMore" -> {
                Txt("本次监测", 20, weight = FontWeight.SemiBold)
                SettingRow("删除本次记录", "同时删除相关录音、标签与统计", "trash-2", true) { vm.sheet = "deleteSession" }
            }
            "deleteClip" -> if (player != null) ConfirmSheet("删除这段录音？", "录音文件会从本机永久删除，报告中的统计与时间轴事件保留。", "删除", { vm.sheet = null }) { vm.deleteRecording(player) }
            "deleteSession" -> if (session != null) ConfirmSheet("删除本次记录？", "本次监测的全部录音（含收藏）、报告、标签与统计都会被删除，此操作无法撤销。", "删除记录", { vm.sheet = null }) { vm.deleteSession(session) }
            "clearUnfav" -> ConfirmSheet("清理未收藏录音？", "全部未收藏的录音文件将从本机删除。收藏的录音、报告统计、标签与趋势保留。", "清理", { vm.sheet = null }, vm::cleanRecordings)
            "clearAll" -> ConfirmSheet("清除全部数据？", "全部录音（含收藏）、报告、标签与趋势将被删除，应用回到首次使用状态。此操作无法撤销。", "全部清除", { vm.sheet = null }, vm::clearAllData)
            "retention" -> {
                Txt("录音保留期限", 20, weight = FontWeight.SemiBold)
                Txt("到期的未收藏录音会被自动清理，报告统计保留。", 13, SleepColors.Secondary)
                Column { listOf(7 to "7 天", 30 to "30 天", 0 to "手动清理").forEach { (days, label) ->
                    RadioRow(label, retention == days, if (days == 0) "不自动清理，注意存储空间" else "超过 $days 天的未收藏录音自动清理") {
                        if (data.events.find { it.id == vm.playerEventId }?.favorite != true) vm.closePlayer()
                        vm.task { vm.repo.setRetention(days) }; vm.sheet = null
                    }
                } }
            }
            "calendar" -> CalendarSheet(data, vm)
            else -> if (player != null) FullPlayer(player, data, vm)
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(20.dp).border(2.dp, if (selected) SleepColors.Primary else Color(0xFF5E6878), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(SleepColors.Primary, CircleShape))
        }
        Column { Txt(label, 15); subtitle?.let { Txt(it, 12, SleepColors.Muted) } }
    }
}

@Composable
private fun ConfirmSheet(title: String, message: String, confirm: String, cancel: () -> Unit, action: () -> Unit) {
    Txt(title, 22, weight = FontWeight.SemiBold)
    Txt(message, 14, SleepColors.Secondary)
    ActionButton(confirm, "trash-2", danger = true, onClick = action)
    ActionButton("取消", "x", primary = false, onClick = cancel)
}

@Composable
fun FullPlayer(event: SoundEvent, data: AppData, vm: SleepViewModel) {
    val color = if (event.correction != null) SleepColors.Muted else if (event.kind == "talk") SleepColors.Talk else SleepColors.Snore
    val session = data.sessions.find { it.id == event.sessionId }
    val list = data.events.filter { it.sessionId == event.sessionId && it.available }
    val index = list.indexOfFirst { it.id == event.id }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Tag(event.label, color); Txt("${index + 1} / ${list.size}", 13, SleepColors.Secondary) }
            Txt(clock(event.startedAt, session?.zoneId ?: java.time.ZoneId.systemDefault().id), 22, weight = FontWeight.SemiBold)
            Txt("${session?.date?.format(DateTimeFormatter.ofPattern("M月d日")) ?: ""} 夜间 · 检测 ${shortDuration(event.detectedMs)} · 相对强度 ${event.strength}", 12, SleepColors.Secondary)
        }
        IconTap("chevron-down", "收起播放器", bg = SleepColors.Elevated) { vm.playerExpanded = false }
    }
    Spacer(Modifier.height(4.dp))
    Waveform(event.waveform, color, Modifier.fillMaxWidth().height(96.dp), if (vm.durationMs > 0) vm.positionMs.toFloat()/vm.durationMs else 0f) { fraction -> vm.seek((fraction * vm.durationMs).toLong()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Txt(playTime(vm.positionMs), 13, SleepColors.Secondary); Txt(playTime(vm.durationMs), 13, SleepColors.Secondary) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconTap("skip-back", "上一段", if (index > 0) SleepColors.Text else SleepColors.Muted) { if (index > 0) vm.skip(-1) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { IconTap("rotate-ccw", "后退十秒") { vm.seek(vm.positionMs - 10000) }; Txt("10 秒", 10, SleepColors.Secondary) }
        IconTap(if (vm.playing) "pause" else "play", if (vm.playing) "暂停播放" else "开始播放", SleepColors.PrimaryInk, SleepColors.Primary, 68, vm::togglePlayback)
        Column(horizontalAlignment = Alignment.CenterHorizontally) { IconTap("rotate-cw", "前进十秒") { vm.seek(vm.positionMs + 10000) }; Txt("10 秒", 10, SleepColors.Secondary) }
        IconTap("skip-forward", "下一段", if (index < list.lastIndex) SleepColors.Text else SleepColors.Muted) { if (index < list.lastIndex) vm.skip(1) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconTap("star", if (event.favorite) "取消收藏" else "收藏录音", if (event.favorite) SleepColors.SnoreText else SleepColors.Text) { vm.task { vm.repo.favorite(event.id) } }
            Txt(if (event.favorite) "已收藏" else "收藏", 12, if (event.favorite) SleepColors.SnoreText else SleepColors.Secondary)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconTap("audio-lines", "修正类型") { vm.correctionChoice = null; vm.sheet = "correct" }; Txt("修正类型", 12, SleepColors.Secondary)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconTap("ellipsis", "录音更多操作") { vm.sheet = "more" }; Txt("更多", 12, SleepColors.Secondary)
        }
    }
}

@Composable
fun MiniPlayer(event: SoundEvent, vm: SleepViewModel) {
    val color = if (event.kind == "talk") SleepColors.Talk else SleepColors.Snore
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF253247)).clickable { vm.playerExpanded = true }.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Column(Modifier.weight(1f)) { Txt("${event.label} · ${clock(event.startedAt)}", 14, weight = FontWeight.Medium, maxLines = 1); Txt("${playTime(vm.positionMs)} / ${playTime(vm.durationMs)}", 12, SleepColors.Secondary) }
        IconTap(if (vm.playing) "pause" else "play", "播放或暂停", onClick = vm::togglePlayback)
        IconTap("x", "关闭播放器", SleepColors.Secondary, onClick = vm::closePlayer)
    }
}

fun playTime(ms: Long): String = "${ms.coerceAtLeast(0)/60000}:${(ms.coerceAtLeast(0)/1000%60).toString().padStart(2,'0')}"

@Composable
private fun CalendarSheet(data: AppData, vm: SleepViewModel) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var chosen by remember { mutableStateOf<LocalDate?>(null) }
    val sessions = data.sessions.filter { it.state == SessionState.FINISHED }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconTap("arrow-left", "上个月") { month = month.minusMonths(1); chosen = null }
        Txt("${month.year} 年 ${month.monthValue} 月", 20, weight = FontWeight.SemiBold)
        IconTap("arrow-right", "下个月") { month = month.plusMonths(1); chosen = null }
    }
    Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Txt(it, 12, SleepColors.Muted) } } }
    val first = month.atDay(1).dayOfWeek.value - 1
    val total = ((first + month.lengthOfMonth() + 6) / 7) * 7
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until total step 7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (row until row + 7).forEach { index ->
                    val day = index - first + 1
                    if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp)) else {
                        val date = month.atDay(day)
                        val records = sessions.filter { it.date == date }
                        val available = records.isNotEmpty()
                        Column(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (chosen == date) SleepColors.Primary.copy(alpha = .16f) else Color.Transparent)
                            .then(if (date == LocalDate.now()) Modifier.border(1.dp, SleepColors.Primary, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable(enabled = available) {
                                if (records.size == 1) { vm.sheet = null; vm.openReport(records.first().id, Screen.HISTORY) }
                                else chosen = date
                            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Txt("$day", 15, if (available) SleepColors.Text else SleepColors.Muted)
                            if (available) Box(Modifier.size(5.dp).clip(CircleShape).background(if (records.all { it.insufficient }) SleepColors.Muted else SleepColors.Primary))
                        }
                    }
                }
            }
        }
    }
    chosen?.let { date -> sessions.filter { it.date == date }.forEach { session ->
        SettingRow("${clock(session.startedAt,session.zoneId)} 开始", durationText(session.effectiveMs)) { vm.sheet = null; vm.openReport(session.id, Screen.HISTORY) }
    } }
    Txt("点击有记录的日期查看报告", 12, SleepColors.Muted)
}
