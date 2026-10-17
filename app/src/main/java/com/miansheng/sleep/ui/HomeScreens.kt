package com.miansheng.sleep.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miansheng.sleep.audio.LiveMonitor
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun OnboardScreen(onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Brand()
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("早晨一分钟，\n听懂昨夜的声音", 32, weight = FontWeight.SemiBold)
                Txt("睡前把手机放在床头，眠声会在夜里安静地记录，早晨给你一份简洁的报告。", 15, SleepColors.Secondary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Triple("audio-lines", "记录鼾声", "统计鼾声累计时长与占比，标出集中出现的时段。"),
                    Triple("message-circle", "捕捉疑似梦话", "夜里的说话声会被单独保存，方便第二天确认。"),
                    Triple("headphones", "早晨回听", "优先推荐值得一听的片段，不用翻完整夜录音。")).forEachIndexed { index, (icon, title, body) ->
                    val color = listOf(SleepColors.Snore, SleepColors.Talk, SleepColors.Primary)[index]
                    SleepCard(padding = 18) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .15f)), contentAlignment = Alignment.Center) { Glyph(icon, color) }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) { Txt(title, 16, weight = FontWeight.SemiBold); Txt(body, 14, SleepColors.Secondary) }
                        }
                    }
                }
            }
            Hint("录音默认仅保存在本机", "shield-check", SleepColors.Muted)
        }
        Box(Modifier.padding(24.dp)) { ActionButton("开始使用", onClick = onContinue) }
    }
}

@Composable
fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(SleepColors.Primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Glyph("moon", SleepColors.Primary, 16) }
        Txt("眠声", 18, weight = FontWeight.SemiBold)
    }
}

@Composable
fun HomeScreen(data: AppData, start: () -> Unit, vm: SleepViewModel) {
    val last = data.sessions.firstOrNull { it.state == SessionState.FINISHED }
    val live = data.live
    val running = live.sessionId != null && live.state != SessionState.FINISHED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Brand(); Txt(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEE", Locale.SIMPLIFIED_CHINESE)), 13, SleepColors.Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(if (running) "晚上好" else if (java.time.LocalTime.now().hour < 12) "早上好" else "你好，今晚也好眠", 28, weight = FontWeight.SemiBold)
            Txt(if (running) "今晚的监测正在进行" else if (last != null) "最近一晚的报告已经准备好" else "从今晚开始了解夜间的声音", 15, SleepColors.Secondary)
        }
        if (running) SleepCard(modifier = Modifier.border(1.dp, SleepColors.Primary.copy(alpha = .35f), RoundedCornerShape(24.dp))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(SleepColors.Primary))
                Txt(if (live.state == SessionState.PAUSED) "监测已暂停" else if (live.state == SessionState.INTERRUPTED) "录音暂不可用" else "监测进行中", 14, SleepColors.Primary)
            }
            Txt(durationText(live.effectiveMs), 36, weight = FontWeight.Medium)
            ActionButton("返回监测", "moon") { vm.screen = Screen.MONITOR }
        } else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1E2B3B), SleepColors.Card)))) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(SleepColors.Primary.copy(alpha = .12f), 75.dp.toPx(), Offset(size.width - 45.dp.toPx(),45.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawCircle(SleepColors.Primary.copy(alpha = .18f), 45.dp.toPx(), Offset(size.width - 45.dp.toPx(),45.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("今晚", 13, SleepColors.Primary)
                    Txt("睡前放好手机，一键开始", 20, weight = FontWeight.SemiBold)
                    Txt("开始前会快速检查麦克风、电量和环境", 14, SleepColors.Secondary)
                }
                ActionButton(if (last == null) "开始第一次监测" else "开始今晚监测", onClick = start)
            }
        }
        if (last != null) {
            val stats = nightStats(last, data.events)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SleepColors.Card)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Txt("最近一晚", 13, SleepColors.Secondary)
                        StatusTag(last, data.segments)
                    }
                    Txt(last.date.format(DateTimeFormatter.ofPattern("M月d日")) + " 夜间", 18, weight = FontWeight.SemiBold)
                    Txt(sessionRange(last), 13, SleepColors.Muted)
                    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt("有效监测", 12, SleepColors.Secondary)
                            Row(verticalAlignment = Alignment.Bottom) { Txt("${last.effectiveMs / 3600000}", 28, weight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline()); Txt("小时", 12, SleepColors.Secondary, modifier = Modifier.alignByBaseline().padding(start = 2.dp, end = 4.dp)); Txt("${last.effectiveMs / 60000 % 60}".padStart(2, '0'), 28, weight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline()); Txt("分", 12, SleepColors.Secondary, modifier = Modifier.alignByBaseline().padding(start = 2.dp)) }
                        }
                        Column(Modifier.weight(1f).borderStart(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt("鼾声", 12, SleepColors.Secondary)
                            Row(verticalAlignment = Alignment.Bottom) { Txt("${stats.snoreMs / 60000}", 28, SleepColors.SnoreText, FontWeight.SemiBold, modifier = Modifier.alignByBaseline()); Txt("分", 12, SleepColors.Secondary, modifier = Modifier.alignByBaseline().padding(start = 2.dp)) }
                            Txt("占比 ${ratioText(stats.ratio)}", 12, SleepColors.Muted)
                        }
                        Column(Modifier.weight(1f).borderStart(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt("疑似梦话", 12, SleepColors.Secondary)
                            Row(verticalAlignment = Alignment.Bottom) { Txt("${stats.talkCount}", 28, SleepColors.TalkText, FontWeight.SemiBold, modifier = Modifier.alignByBaseline()); Txt("段", 12, SleepColors.Secondary, modifier = Modifier.alignByBaseline().padding(start = 2.dp)) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SleepColors.Border))
                Row(Modifier.fillMaxWidth().height(56.dp).clickable { vm.openReport(last.id, Screen.HOME) }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Txt("查看报告", 15, SleepColors.Primary, FontWeight.Medium); Glyph("chevron-right", SleepColors.Primary, 20)
                }
            }
            if (last.recovered) Hint("上次监测意外中断，已保留可用记录。", "triangle-alert", SleepColors.Danger)
            SleepCard(color = Color(0xFF161F2C), padding = 16) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Glyph("smartphone", SleepColors.Secondary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Txt("摆放提示", 14, weight = FontWeight.Medium); Txt("手机放在床头，距枕边约 30–50 厘米，麦克风朝向你、不要被遮挡。", 13, SleepColors.Secondary) }
                }
            }
        } else SleepCard(color = Color(0xFF161F2C), padding = 24) {
            MoonIllustration(Modifier.fillMaxWidth().height(170.dp))
            Txt("还没有监测记录", 22, weight = FontWeight.SemiBold)
            Txt("今晚睡前开始第一次监测。明早你会看到鼾声时长、疑似梦话片段，以及值得回听的录音。", 14, SleepColors.Secondary)
            Hint("录音默认仅保存在本机", "shield-check", SleepColors.Muted)
        }
    }
}

private fun Modifier.borderStart() = this.drawBehind { drawLine(SleepColors.Border, Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }.padding(start = 14.dp)

@Composable
fun StatusTag(session: SleepSession, segments: List<CaptureSegment>) {
    val partial = session.recovered || segments.any { it.sessionId == session.id && it.kind == "gap" }
    val label = if (session.insufficient) "数据不足" else if (partial) "部分中断" else "完整记录"
    Tag(label, if (session.insufficient) SleepColors.Secondary else if (partial) SleepColors.Danger else SleepColors.PrimaryText)
}

@Composable
fun MoonIllustration(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            drawCircle(SleepColors.Primary.copy(alpha = .1f), style = Stroke(1.dp.toPx()))
            drawCircle(SleepColors.Primary.copy(alpha = .18f), radius = 61.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(SleepColors.Primary.copy(alpha = .12f), radius = 38.dp.toPx())
            drawCircle(SleepColors.Snore.copy(alpha = .7f), 5.dp.toPx(), Offset(12.dp.toPx(), 38.dp.toPx()))
            drawCircle(SleepColors.Talk.copy(alpha = .8f), 4.dp.toPx(), Offset(155.dp.toPx(), 130.dp.toPx()))
        }
        Glyph("moon", SleepColors.Primary, 30)
    }
}

@Composable
fun PrecheckScreen(vm: SleepViewModel) {
    val state = vm.precheck
    Column(Modifier.fillMaxSize()) {
        PageTitle("开始前检查", vm::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SleepCard {
                PlacementIllustration()
                Txt("放在床头，麦克风朝向你", 16, weight = FontWeight.SemiBold)
                Txt("不要压在枕头下或被被子盖住；建议连接充电器，并开启勿扰模式。", 13, SleepColors.Secondary)
            }
            SleepCard(padding = 0) {
                CheckRow("mic", "麦克风", if (state.checking) "正在检查…" else if (state.micOk) "可以正常使用" else "暂不可用", state.checking, !state.micOk)
                CheckRow("battery", "当前电量", if (state.checking) "正在检查…" else "${state.battery}%" + if (state.battery < 30) " · 建议连接充电器" else " · 建议整夜充电", state.checking, false, state.battery < 30)
                CheckRow("hard-drive", "存储空间", if (state.checking) "正在检查…" else "可用 ${state.freeMb} MB", state.checking, state.freeMb < 50)
                CheckRow("volume-2", "环境声音", if (state.checking) "正在听取周围声音…" else if (state.noisy) "背景声音较多" else "环境较安静", state.checking, false, state.noisy)
            }
            if (!state.checking && (state.noisy || state.error != null || state.freeMb < 50)) SleepCard(color = SleepColors.Danger.copy(alpha = .1f), padding = 16) {
                Hint(state.error ?: if (state.freeMb < 50) "存储空间不足，请至少留出 50 MB 后重试。" else "风扇、空调或电视声可能影响识别。可以关闭后重新检测，也可以直接继续。", "triangle-alert", SleepColors.Danger)
            }
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (state.noisy) "仍然开始监测" else "开始监测", "moon", enabled = !state.checking && state.micOk && state.freeMb >= 50, onClick = vm::startMonitoring)
            if (!state.checking) ActionButton("重新检测", "refresh-cw", primary = false, onClick = vm::runPrecheck)
        }
    }
}

@Composable
private fun CheckRow(icon: String, title: String, text: String, checking: Boolean, error: Boolean, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF222E3F)), contentAlignment = Alignment.Center) { Glyph(icon, size = 20) }
        Column(Modifier.weight(1f)) { Txt(title, 15); Txt(text, 13, if (error && !checking) SleepColors.Danger else SleepColors.Secondary) }
        if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SleepColors.Muted)
        else Glyph(if (error) "circle-x" else if (warn) "triangle-alert" else "circle-check", if (error) SleepColors.Danger else if (warn) SleepColors.Snore else SleepColors.Primary)
    }
}

@Composable
private fun PlacementIllustration() {
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(SleepColors.Nav)) {
        val s = size.width / 332
        fun rect(c: Color, x: Float, y: Float, w: Float, h: Float, r: Float) = drawRoundRect(c, Offset(x*s,y*s), Size(w*s,h*s), CornerRadius(r*s))
        rect(Color(0xFF222E3F),20f,44f,64f,70f,10f)
        rect(SleepColors.Primary,40f,58f,22f,40f,5f)
        rect(SleepColors.Background,42f,60f,18f,36f,3f)
        rect(Color(0xFF222E3F),150f,18f,166f,114f,14f)
        rect(Color(0xFF2E3B50),160f,34f,58f,82f,12f)
        drawCircle(Color(0xFF4A5A72),17*s,Offset(189*s,73*s))
        rect(Color(0xFF283548),218f,30f,88f,90f,10f)
        repeat(3) { i -> val radius = (20+i*10)*s; drawArc(SleepColors.Primary.copy(alpha=.7f-i*.2f),-50f,100f,false,Offset(86*s-radius,66*s-radius),Size(radius*2,radius*2),style=Stroke(1.5f*s)) }
        drawLine(SleepColors.Muted,Offset(92*s,124*s),Offset(148*s,124*s),1.5f*s,pathEffect=PathEffect.dashPathEffect(floatArrayOf(4*s,4*s)))
    }
}

@Composable
fun DeniedScreen(vm: SleepViewModel, settings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageTitle("", vm::back)
        Column(Modifier.weight(1f).padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(SleepColors.Danger.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Glyph("mic-off", SleepColors.Danger, 32) }
            Txt("没有麦克风权限，\n暂时无法监测", 26, weight = FontWeight.SemiBold)
            Txt("眠声只在监测期间使用麦克风，声音识别在本机完成。请在系统应用权限中允许使用麦克风。", 15, SleepColors.Secondary)
            SleepCard { Txt("在系统设置中开启", 13, SleepColors.Secondary); Txt("设置 › 应用 › 眠声 › 权限 › 麦克风", 14) }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { ActionButton("前往设置", "arrow-up-right", onClick = settings); ActionButton("返回首页", "house", primary = false, onClick = vm::back) }
    }
}

@Composable
fun MonitorScreen(live: LiveMonitor, vm: SleepViewModel) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val paused = live.state == SessionState.PAUSED
    val interrupted = live.state == SessionState.INTERRUPTED
    val inactive = live.state == SessionState.FINISHED
    val infinite = rememberInfiniteTransition(label = "quiet breathing")
    val breath by infinite.animateFloat(.4f, 1f, infiniteRepeatable(tween(6500, easing = LinearEasing)), label = "breathing ring")
    Column(Modifier.fillMaxSize().background(SleepColors.Night)) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconTap("chevron-down", "返回首页", SleepColors.Muted) { vm.screen = Screen.HOME }
            IconTap("bell", "查看通知说明", SleepColors.Muted) { vm.snack("下拉系统通知栏，可查看监测状态与暂停操作") }
        }
        if (inactive && live.sessionId == null) {
            EmptyState(if (live.message.isBlank()) "正在准备监测" else "监测未能启动", live.message.ifBlank { "正在打开麦克风并准备声音识别" }, "mic", Modifier.padding(28.dp))
            if (live.message.isNotBlank()) Box(Modifier.padding(24.dp)) { ActionButton("返回首页", "house") { vm.screen = Screen.HOME } }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Tag(if (inactive) "监测已结束" else if (paused) "已暂停" else if (interrupted) "录音暂不可用" else "正在监测", if (paused) SleepColors.Muted else if (interrupted) SleepColors.Danger else Color(0xFF5FA79C))
                Spacer(Modifier.height(18.dp))
                Txt(clock(now), 92, Color(0xFFB9B3A9), FontWeight.Light, numeric = true)
                Txt(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)), 14, Color(0xFF5E6878))
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(220.dp)) {
                        repeat(3) { i -> val progress = if (paused || interrupted) .5f else (breath + i * .25f) % 1f
                            drawCircle(Color(0xFF3E786F).copy(alpha = (1-progress)*.3f), radius = size.minDimension / 2 * (.35f + progress * .65f), style = Stroke(1.dp.toPx())) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(18, 30, 40, 30, 18).forEachIndexed { i, height -> Box(Modifier.width(4.dp).height((height * if (paused || interrupted) .25f else .4f + breath * .6f).dp).clip(CircleShape).background(Color(0xFF4F8D84))) }
                    }
                }
                Row(Modifier.fillMaxWidth().drawBehind { drawLine(Color(0xFF121A24),Offset.Zero,Offset(size.width,0f),1.dp.toPx()); drawLine(Color(0xFF121A24),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx()) }) {
                    Column(Modifier.weight(1f).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Txt("有效监测", 12, Color(0xFF5E6878)); Txt(hms(live.effectiveMs), 24, Color(0xFFA39D94), FontWeight.Medium) }
                    Column(Modifier.weight(1f).borderStart().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Txt("开始时间", 12, Color(0xFF5E6878)); Txt(clock(live.startedAt), 24, Color(0xFFA39D94), FontWeight.Medium) }
                }
                Spacer(Modifier.height(14.dp))
                Txt(if (paused) "暂停期间不录音，报告时间轴会标记为暂停。" else if (interrupted) live.message + "。正在尝试恢复，缺失时段不会计入有效监测。" else "可以锁屏，监测会在后台继续。", 13, Color(0xFF5E6878), modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (paused) "继续监测" else "暂停", if (paused) "play" else "pause", Modifier.weight(1f), primary = false, enabled = !inactive && !live.stopping, onClick = vm::togglePause)
                ActionButton("结束监测", "square", Modifier.weight(1f), primary = false, enabled = !inactive && !live.stopping) { vm.sheet = "end" }
            }
        }
    }
}

fun hms(ms: Long): String = "%02d:%02d:%02d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

@Composable
fun GeneratingScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(48.dp), color = SleepColors.Primary, strokeWidth = 3.dp)
        Spacer(Modifier.height(28.dp))
        Txt("正在生成报告", 24, weight = FontWeight.SemiBold)
        Txt("正在保存本次监测的声音与时间轴", 14, SleepColors.Secondary)
        Spacer(Modifier.height(28.dp))
        listOf("整理录音片段", "保存声音事件", "汇总时间轴与统计").forEach { Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Glyph("circle-check", SleepColors.Primary, 20); Txt(it, 15, SleepColors.Secondary) } }
    }
}
