package com.miansheng.sleep

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.miansheng.sleep.audio.*
import com.miansheng.sleep.data.*
import com.miansheng.sleep.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: SleepViewModel
    private lateinit var app: SleepApplication
    private val fixtureIds = mutableListOf<String>()

    @Before fun setup() {
        app = compose.activity.application as SleepApplication
        runBlocking { app.ready.await() }
        vm = ViewModelProvider(compose.activity)[SleepViewModel::class.java]
        compose.runOnIdle { vm.finishOnboard() }
        compose.waitUntil(10000) { vm.loaded }
    }
    @After fun cleanup() {
        if (MonitorService.live.value.state != SessionState.FINISHED) {
            compose.runOnIdle { MonitorService.command(compose.activity,MonitorService.STOP) }
            compose.waitUntil(10000) { MonitorService.live.value.state == SessionState.FINISHED }
        }
        compose.runOnIdle { vm.closePlayer() }
        runBlocking { fixtureIds.forEach { app.repository.deleteSession(it) } }
    }

    @Test(timeout = 120000) fun reportPlaybackCorrectionAndHistoryUsePersistentData() {
        val fixture = seedPrototypeNight()
        compose.waitUntil(10000) { vm.data.value.sessions.any { it.id == fixture.id } }
        compose.runOnIdle { vm.screen=Screen.HOME }
        compose.onNodeWithText("查看报告").assertExists()
        screenshot("home")
        compose.runOnIdle { vm.openReport(fixture.id,Screen.HOME) }
        compose.onNodeWithText("监测报告").assertExists()
        compose.onNodeWithText("42").assertExists()
        compose.onNodeWithText("占比 10%").assertExists()
        screenshot("report")

        compose.onNodeWithText("查看全部录音 · 9 段").performScrollTo().performClick()
        compose.onNodeWithText("疑似梦话 3").performClick()
        screenshot("recordings")
        val talk = vm.data.value.events.first { it.sessionId==fixture.id && it.kind=="talk" }
        compose.onNodeWithContentDescription("播放 ${talk.label} ${clock(talk.startedAt,fixture.zoneId)}").performClick()
        compose.waitUntil(5000) { vm.durationMs > 0 }
        screenshot("player")
        compose.runOnIdle { vm.pausePlayback() }
        compose.onNodeWithContentDescription("录音播放进度").performTouchInput { swipe(centerLeft, centerRight, 400) }
        compose.waitUntil(5000) { vm.positionMs > vm.durationMs / 2 }
        compose.onAllNodesWithContentDescription("收藏录音").onLast().performClick()
        compose.waitUntil(5000) { vm.data.value.events.find { it.id==talk.id }?.favorite==true }
        compose.onNodeWithContentDescription("修正类型").performClick()
        compose.onNodeWithText("电视或音乐").performClick()
        compose.onNodeWithText("确认修正").performClick()
        compose.waitUntil(5000) { vm.data.value.events.find { it.id==talk.id }?.correction=="电视或音乐" }
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5000) { vm.data.value.events.find { it.id==talk.id }?.correction==null }
        compose.runOnIdle { vm.closePlayer();vm.screen=Screen.REPORT;vm.draftTags=listOf("侧睡");vm.draftNote="测试备注";vm.sheet="tags" }
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(5000) { vm.data.value.sessions.find { it.id==fixture.id }?.tags==listOf("侧睡") }
        compose.runOnIdle { vm.screen=Screen.HISTORY }
        compose.onAllNodesWithText("记录",useUnmergedTree=true).assertCountEquals(2)
        screenshot("history")
        compose.runOnIdle { vm.screen=Screen.TRENDS }
        screenshot("trends")
        compose.runOnIdle { vm.screen=Screen.SETTINGS }
        screenshot("settings")
    }

    @Test(timeout = 120000) fun realMicrophoneSessionSurvivesScreenOffAndPauseExcludesTime() {
        compose.runOnIdle { vm.runPrecheck() }
        compose.waitUntil(10000) { !vm.precheck.checking }
        assertTrue(vm.precheck.error,vm.precheck.micOk)
        screenshot("precheck")
        compose.onNodeWithText("开始监测").performClick()
        compose.waitUntil(15000) { MonitorService.live.value.effectiveMs >= 1900 }
        val id=MonitorService.live.value.sessionId!!
        fixtureIds+=id
        screenshot("monitor")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val beforeLock=MonitorService.live.value.effectiveMs
        device.sleep()
        try {
            compose.waitUntil(10000) { MonitorService.live.value.effectiveMs>=beforeLock+1900 }
        } finally { device.wakeUp() }
        compose.runOnIdle { MonitorService.command(compose.activity,MonitorService.PAUSE) }
        compose.waitUntil(5000) { MonitorService.live.value.state==SessionState.PAUSED }
        val paused=MonitorService.live.value.effectiveMs
        // A real delay verifies that wall-clock time while paused does not become effective audio.
        Thread.sleep(1500)
        assertEquals(paused,MonitorService.live.value.effectiveMs)
        compose.runOnIdle { MonitorService.command(compose.activity,MonitorService.RESUME) }
        compose.waitUntil(10000) { MonitorService.live.value.effectiveMs>=paused+1900 }
        compose.runOnIdle { vm.finishMonitoring() }
        compose.waitUntil(10000) { vm.screen==Screen.REPORT }
        val saved=runBlocking { app.repository.dao.session(id) }!!
        assertTrue(saved.effectiveMs>0)
        assertNotNull(saved.endedAt)
        assertTrue(saved.effectiveMs<saved.endedAt!!-saved.startedAt)
        assertTrue(runBlocking { app.repository.dao.segments(id) }.any { it.kind=="pause" && it.endedAt!!>it.startedAt })
        compose.onNodeWithText("本次没有有效录音数据，无法生成完整统计。").assertDoesNotExist()
        screenshot("short-session-report")
    }

    private fun seedPrototypeNight():SleepSession = runBlocking {
        val zone=ZoneId.of("Asia/Shanghai")
        val start=LocalDate.now(zone).minusDays(1).atTime(23,30).atZone(zone).toInstant().toEpochMilli()
        val session=SleepSession(id="ui-fixture-${UUID.randomUUID()}",startedAt=start,endedAt=start+450*60000L,
            zoneId=zone.id,state=SessionState.FINISHED,effectiveMs=420*60000L,heartbeatAt=start+450*60000L)
        fixtureIds+=session.id
        app.repository.dao.insertSession(session)
        val pcm=ShortArray(16000*3) { (sin(it*2*Math.PI*180/16000)*sin(it*Math.PI/16000)*7000).toInt().toShort() }
        val definitions=listOf(Triple("snore",72,360),Triple("snore",110,240),Triple("talk",142,8),Triple("snore",181,720),Triple("talk",258,14),Triple("snore",280,480),Triple("snore",332,300),Triple("talk",366,5),Triple("snore",405,420))
        definitions.forEach { (kind,offset,seconds) ->
            val id=UUID.randomUUID().toString()
            val file=File(app.repository.recordingsDir,"$id.m4a")
            ClipWriter.write(file,pcm)
            app.repository.dao.insertEvent(SoundEvent(id=id,sessionId=session.id,startedAt=start+offset*60000L,endedAt=start+offset*60000L+seconds*1000L,
                kind=kind,detectedMs=seconds*1000L,confidence=.8f,rms=.04f,clipPath=file.absolutePath,waveform=ClipWriter.waveform(pcm)))
        }
        app.repository.dao.insertSegment(CaptureSegment(sessionId=session.id,startedAt=start+215*60000L,endedAt=start+225*60000L,kind="pause",reason="手动暂停，此时段未录音"))
        app.repository.dao.insertSegment(CaptureSegment(sessionId=session.id,startedAt=start+310*60000L,endedAt=start+330*60000L,kind="gap",reason="测试夹具：模拟麦克风占用"))
        session
    }

    private fun screenshot(name:String) {
        compose.runOnIdle { vm.snackbar = null; vm.undo = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // UiDevice captures the actual display; allow the committed Compose frame to reach it.
        Thread.sleep(250)
        val outputPath=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory=File(outputPath ?: File(app.filesDir,"ui-test-screenshots").absolutePath).apply { mkdirs() }
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory,"$name.png")))
    }
}
