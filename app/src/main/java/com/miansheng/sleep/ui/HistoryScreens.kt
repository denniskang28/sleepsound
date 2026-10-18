@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.miansheng.sleep.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.miansheng.sleep.data.*
import com.miansheng.sleep.domain.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

@Composable
fun HistoryScreen(data: AppData, vm: SleepViewModel) {
    val sessions = data.sessions.filter { it.state == SessionState.FINISHED }
    val groups = sessions.groupBy { it.date }
    val recent = List(10) { LocalDate.now().minusDays(it.toLong()) }
    val dates = (groups.keys + recent).sortedDescending()
    Column(Modifier.fillMaxSize()) {
        PageTitle("记录", trailing = { IconTap("calendar", "选择记录日期") { vm.sheet = "calendar" } })
        if (sessions.isEmpty()) Box(Modifier.padding(horizontal = 20.dp)) { EmptyState("还没有记录", "完成第一次监测后，每晚的报告会按日期出现在这里。", "calendar-days") }
        else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend("完整记录",SleepColors.Primary); Legend("部分中断",SleepColors.Danger); Legend("数据不足",SleepColors.Muted); Legend("未监测",SleepColors.Border)
                }
            }
            dates.forEach { date ->
                if (date.dayOfMonth == 1 || date == dates.first()) item(key = "month$date") { Txt(date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")), 13, SleepColors.Muted, modifier = Modifier.padding(vertical = 6.dp)) }
                val nights = groups[date]
                if (nights == null) item(key = date.toString()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp,SleepColors.Border,RoundedCornerShape(20.dp)).padding(18.dp), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        DateNumber(date, true); Txt("当天未监测",14,SleepColors.Muted)
                    }
                } else items(nights, key = { it.id }) { session ->
                    val stats = nightStats(session,data.events)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SleepColors.Card).clickable { vm.openReport(session.id,Screen.HISTORY) }.padding(18.dp), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        DateNumber(date)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Txt(if (nights.size > 1) clock(session.startedAt,session.zoneId)+" 开始" else "夜间监测",14,Color(0xFFD8D2C8))
                                StatusTag(session,data.segments)
                            }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Txt("有效 ${durationText(session.effectiveMs)}",12,SleepColors.Secondary)
                                Txt("鼾声 ${shortDuration(stats.snoreMs)}",12,SleepColors.SnoreText)
                                Txt("疑似梦话 ${stats.talkCount} 段",12,SleepColors.TalkText)
                            }
                            if (data.events.any { it.sessionId==session.id && !it.available }) Txt("部分录音已清理或不可用",12,SleepColors.Muted)
                        }
                        Glyph("chevron-right",SleepColors.Muted,18)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateNumber(date: LocalDate, muted: Boolean = false) {
    Column(Modifier.width(44.dp)) {
        Txt("${date.dayOfMonth}".padStart(2,'0'),if(muted)20 else 26,if(muted)SleepColors.Muted else SleepColors.Text,FontWeight.SemiBold)
        Txt(date.format(DateTimeFormatter.ofPattern("EEE",Locale.SIMPLIFIED_CHINESE)),12,SleepColors.Secondary)
    }
}

data class TrendDay(val date: LocalDate,val sessions:List<SleepSession>,val snoreMs:Long,val talks:Int,val ratio:Double?)
fun trendDays(data:AppData, range:Int):List<TrendDay> = List(range) { index ->
    val date=LocalDate.now().minusDays((range-1-index).toLong())
    val sessions=data.sessions.filter { it.state==SessionState.FINISHED && it.date==date }
    val stats=sessions.map { nightStats(it,data.events) }
    val valid=sessions.filter { !it.insufficient }
    val totalEffective=valid.sumOf { it.effectiveMs }
    TrendDay(date,sessions,stats.sumOf { it.snoreMs },stats.sumOf { it.talkCount },
        if(totalEffective>0)valid.sumOf { nightStats(it,data.events).snoreMs }.toDouble()/totalEffective else null)
}

@Composable
fun TrendsScreen(data:AppData,vm:SleepViewModel) {
    val days=trendDays(data,vm.trendRange)
    val selected=days.find { it.date==vm.selectedTrendDate } ?: days.lastOrNull { it.sessions.isNotEmpty() } ?: days.last()
    val hasData=data.sessions.any { it.state==SessionState.FINISHED }
    Column(Modifier.fillMaxSize()) {
        PageTitle("趋势")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(CircleShape).border(1.dp,SleepColors.Border,CircleShape)) {
                listOf(7,30).forEach { range ->
                    val active=vm.trendRange==range
                    Row(Modifier.weight(1f).height(44.dp).background(if(active)SleepColors.Primary.copy(alpha=.16f) else Color.Transparent).clickable { vm.trendRange=range;vm.selectedTrendDate=null }.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(active)Glyph("check",SleepColors.PrimaryText,16)
                        Txt("近 $range 天",14,if(active)SleepColors.PrimaryText else SleepColors.Secondary)
                    }
                }
            }
            if(!hasData) EmptyState("暂无趋势","完成监测后，这里会显示鼾声与疑似梦话的变化。","chart-column")
            else {
                Txt("近 ${vm.trendRange} 天监测 ${days.count { it.sessions.isNotEmpty() }} 天，未监测 ${days.count { it.sessions.isEmpty() }} 天。点击日期查看当晚。",13,SleepColors.Secondary)
                SleepCard(color=Color(0xFF222E3F),padding=16) {
                    Txt(selected.date.format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.SIMPLIFIED_CHINESE)),15,weight=FontWeight.SemiBold)
                    Txt(if(selected.sessions.isEmpty())"当天未监测" else "鼾声 ${shortDuration(selected.snoreMs)} · 占比 ${ratioText(selected.ratio)} · 疑似梦话 ${selected.talks} 段",13,SleepColors.Secondary)
                    if(selected.sessions.isNotEmpty()) {
                        if(selected.sessions.size==1) Row(Modifier.heightIn(min=48.dp).clickable { vm.openReport(selected.sessions.first().id,Screen.TRENDS) },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) { Txt("查看报告",14,SleepColors.Primary);Glyph("chevron-right",SleepColors.Primary,16) }
                        else selected.sessions.forEach { session -> Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable { vm.openReport(session.id,Screen.TRENDS) },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) { Txt("${clock(session.startedAt,session.zoneId)} 开始 · ${durationText(session.effectiveMs)}",13);Glyph("chevron-right",SleepColors.Primary,16) } }
                    }
                }
                TrendChart("鼾声累计时长","单位：分钟",days,selected.date,SleepColors.Snore,0,{it.snoreMs/60000.0},{vm.selectedTrendDate=it})
                TrendChart("鼾声占比","鼾声 ÷ 有效监测时长",days,selected.date,SleepColors.Snore,1,{it.ratio?.times(100)},{vm.selectedTrendDate=it})
                TrendChart("疑似梦话","单位：段",days,selected.date,SleepColors.Talk,2,{if(it.sessions.isEmpty())null else it.talks.toDouble()},{vm.selectedTrendDate=it})
                SleepCard(padding=18) {
                    Txt("生活标签回顾",15,weight=FontWeight.SemiBold)
                    val sessions=data.sessions.filter { !it.insufficient && it.state==SessionState.FINISHED && it.date>=days.first().date && it.date<=days.last().date }
                    val tags=listOf("饮酒","鼻塞","侧睡","熬夜","运动").filter { tag -> sessions.any { tag in it.tags } }
                    if(tags.isEmpty())Txt("在报告里添加生活标签，方便回顾当天的习惯。",13,SleepColors.Secondary)
                    tags.forEach { tag ->
                        val tagged=sessions.filter { tag in it.tags };val other=sessions.filter { tag !in it.tags }
                        Txt("$tag · ${tagged.size} 次监测",14,SleepColors.PrimaryText)
                        Txt("平均鼾声 ${durationText(tagged.sumOf { nightStats(it,data.events).snoreMs } / tagged.size)}",13,SleepColors.Secondary)
                        if(other.isNotEmpty())Txt("其他记录平均 ${durationText(other.sumOf { nightStats(it,data.events).snoreMs } / other.size)}",12,SleepColors.Muted)
                    }
                    Txt("这是记录中观察到的差异，不代表标签与鼾声之间存在因果关系。数据不足的会话不计入占比与平均。",12,SleepColors.Muted)
                }
            }
        }
    }
}

@Composable
private fun TrendChart(title:String,unit:String,days:List<TrendDay>,selected:LocalDate,color:Color,kind:Int,value:(TrendDay)->Double?,select:(LocalDate)->Unit) {
    val values=days.map { if(it.sessions.isEmpty())null else value(it) }
    val observed=values.filterNotNull().maxOrNull() ?: 0.0
    val maxValue=when(kind) { 0->max(60.0,ceil(observed/30)*30);1->max(16.0,ceil(observed/8)*8);else->max(4.0,ceil(observed/2)*2) }
    SleepCard(padding=16) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Txt(title,15,weight=FontWeight.SemiBold);Txt(unit,11,SleepColors.Muted) }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(28.dp).height(140.dp),verticalArrangement=Arrangement.SpaceBetween) {
                listOf(maxValue,maxValue/2,0.0).forEach { Txt("${it.toInt()}"+if(kind==1)"%" else "",10,SleepColors.Muted) }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription="$title，最近 ${days.size} 天；点击选择日期" }
                    .pointerInput(days) { detectTapGestures { offset -> select(days[(offset.x/size.width*days.size).toInt().coerceIn(days.indices)].date) } }) {
                    val width=size.width/days.size
                    listOf(0f,size.height/2,size.height).forEach { y->drawLine(SleepColors.Border,Offset(0f,y),Offset(size.width,y),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))) }
                    var previous:Offset?=null
                    days.forEachIndexed { i,day ->
                        if(day.date==selected)drawRoundRect(SleepColors.Text.copy(alpha=.07f),Offset(i*width,0f),Size(width,size.height),CornerRadius(6.dp.toPx()))
                        val v=values[i]
                        if(v==null) { previous=null; if(kind!=1)drawLine(SleepColors.Border,Offset((i+.25f)*width,size.height-3.dp.toPx()),Offset((i+.75f)*width,size.height-3.dp.toPx()),2.dp.toPx());return@forEachIndexed }
                        val height=(v/maxValue*size.height).toFloat().coerceAtLeast(if(v>0)2.dp.toPx() else 0f)
                        val center=Offset((i+.5f)*width,size.height-height)
                        if(kind==1) {
                            previous?.let { drawLine(color,it,center,2.dp.toPx()) }
                            drawCircle(SleepColors.Background,3.dp.toPx(),center)
                            drawCircle(color,3.dp.toPx(),center,style=Stroke(1.8.dp.toPx()))
                            previous=center
                        } else if(height>0)drawRoundRect(if(day.ratio==null)SleepColors.Muted else color,Offset(i*width+width*.2f,size.height-height),Size(width*.6f,height),CornerRadius(3.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=8.dp)) {
                    days.forEachIndexed { index,day -> Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                        if(days.size==7 || index%5==4 || index==days.lastIndex)Txt(if(days.size==7)"${day.date.monthValue}/${day.date.dayOfMonth}" else "${day.date.dayOfMonth}",10,if(day.date==selected)SleepColors.Text else SleepColors.Muted)
                    } }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(data:AppData,retention:Int,vm:SleepViewModel) {
    val context=LocalContext.current
    val favoriteBytes=data.events.filter { it.favorite && it.available }.sumOf { File(it.clipPath!!).length() }
    val otherBytes=data.events.filter { !it.favorite && it.available }.sumOf { File(it.clipPath!!).length() }
    val total=favoriteBytes+otherBytes
    fun mb(value:Long)="%.1f".format(Locale.US,value/1048576.0)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.offset(x=(-20).dp)) { PageTitle("设置") }
        Txt("录音与存储",13,SleepColors.Primary)
        SleepCard(padding=0) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Txt("当前录音占用",15);Txt("${mb(total)} MB",22,weight=FontWeight.SemiBold) }
                Canvas(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(SleepColors.Elevated)) {
                    val fraction=if(total>0)favoriteBytes.toFloat()/total else 0f
                    drawRect(SleepColors.Snore,size=Size(size.width*fraction,size.height));drawRect(SleepColors.Primary,Offset(size.width*fraction,0f),Size(size.width*(1-fraction),size.height))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) { Legend("收藏 ${mb(favoriteBytes)} MB",SleepColors.Snore);Legend("其他 ${mb(otherBytes)} MB",SleepColors.Primary) }
            }
            SettingRow("录音保留期限",(if(retention==0)"手动清理" else "$retention 天")+" · 收藏的录音始终保留") { vm.sheet="retention" }
            SettingRow("清理未收藏录音","统计数据会保留") { vm.sheet="clearUnfav" }
        }
        Hint("录音默认仅保存在本机，不会上传。收藏的录音受保护，不会被自动清理。","shield-check")
        Txt("声音测量",13,SleepColors.Primary,modifier=Modifier.padding(top=6.dp))
        val calibrationId by vm.activeCalibrationId.collectAsState()
        SleepCard(padding=0) {
            SettingRow("声音校准",if(calibrationId==null)"未校准 · 显示相对电平 dBFS" else "已保存校准 · 使用时核对麦克风配置","audio-lines",onClick=vm::openCalibration)
        }
        Txt("权限与通知",13,SleepColors.Primary,modifier=Modifier.padding(top=6.dp))
        SleepCard(padding=0) {
            val allowed=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            SettingRow("麦克风权限",if(allowed)"已允许" else "未允许","mic") { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }
            SettingRow("通知设置",icon="bell") { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }
        }
        Txt("帮助",13,SleepColors.Primary,modifier=Modifier.padding(top=6.dp))
        SleepCard(padding=0) {
            SettingRow("使用说明",icon="book-open") { vm.screen=Screen.HELP }
            SettingRow("清除全部数据",icon="trash-2",danger=true) { vm.sheet="clearAll" }
        }
        Txt("眠声 ${com.miansheng.sleep.BuildConfig.VERSION_NAME}",12,Color(0xFF5E6878),modifier=Modifier.padding(4.dp))
    }
}

@Composable
fun SettingRow(title:String,subtitle:String?=null,icon:String?=null,danger:Boolean=false,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(onClick=onClick).padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        if(icon!=null)Glyph(icon,if(danger)SleepColors.Danger else SleepColors.Secondary,20)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) { Txt(title,15,if(danger)SleepColors.Danger else SleepColors.Text);subtitle?.let { Txt(it,13,SleepColors.Secondary) } }
        Glyph("chevron-right",SleepColors.Muted,18)
    }
}

@Composable
fun HelpScreen(vm:SleepViewModel) {
    Column(Modifier.fillMaxSize()) {
        PageTitle("使用说明",vm::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            listOf("睡前放好手机" to "将手机放在距枕边约 30–50 厘米的床头位置，麦克风朝向你并保持无遮挡。建议连接充电器。",
                "主动开始监测" to "在应用中点击开始，完成权限授权与环境检查。锁屏后继续监测，可以通过系统通知暂停或返回应用。",
                "早晨回听" to "结束监测后查看报告，回听鼾声和疑似梦话。检测可能受风扇、电视和同床者影响，可以手动修正误判。",
                "理解监测数据" to "有效监测时长来自成功采集的声音，不等于睡眠时长。少于 3 小时的会话显示数据不足，占比不计算。没有检测到声音事件不代表没有发生。",
                "录音保留与隐私" to "默认仅在本机识别并保存事件片段，不上传声音。可以设置保留期限，收藏片段不会被自动清理。主动分享的录音将交给你选择的应用。",
                "理解声音强度" to "未校准显示 dBFS，相对满刻度 0 dBFS，越接近 0 信号越强。校准后为手机位置处的限带估计 dBA。平均按声能计算；最高为约 0.975 秒窗口最大值；P95 是升序第 95 百分位，不是声学 L95。无有效信号与过载不计入统计，波形为相对振幅。",
                "校准声音" to "在设置中进入声音校准，使用参考声级计的 A 计权 LAeq，完成至少三组稳定声源比对；相邻参考值差至少 5 dB，总跨度至少 15 dB。只在比对范围内显示估计数值，换手机、麦克风或录音配置后重新校准。",
                "识别能力说明" to "当前使用通用预训练声音模型，尚未完成真实卧室准确度验证。疑似梦话仅表示检测到人声，无法确认说话者和睡眠状态。首版不提供睡眠分期或医疗诊断。").forEachIndexed { index,(title,body) ->
                SleepCard { Txt("${index+1}. $title",16,weight=FontWeight.SemiBold);Txt(body,14,SleepColors.Secondary) }
            }
        }
    }
}
