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
import com.miansheng.sleep.domain.*
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
        compose.runOnIdle { vm.calibration.cancel(); vm.closePlayer() }
        runBlocking { fixtureIds.forEach { app.repository.deleteSession(it) } }
    }

    @Test(timeout=90000) fun calibrationWizardSavesAndDisablesWithoutRewritingHistoricalLevels() {
        val oldId=app.repository.activeCalibrationId.value
        try {
            compose.runOnIdle { vm.screen=Screen.SETTINGS }
            compose.onNodeWithText("声音校准").performScrollTo().performClick()
            compose.onNodeWithText("声音校准").assertExists()
            screenshot("sound-calibration-setup")
            listOf(35.0,45.0,55.0).forEach { reference ->
                compose.runOnIdle {
                    vm.calibration.state.value=vm.calibration.state.value.copy(pending=CalibrationCapture(reference-90.0,1.0,30225,
                        "test-fixture-only","测试夹具（非真实校准）"))
                }
                compose.onNodeWithText("参考仪器 LAeq（dBA）").performScrollTo().performTextInput(reference.toString())
                compose.onNodeWithText("保存本组参考值").performScrollTo().performClick()
            }
            compose.onNodeWithText("参考声级计型号／编号").performScrollTo().performTextInput("仅测试参考仪器")
            compose.onNodeWithText("保存并启用校准").performScrollTo().performClick()
            compose.waitUntil(10000){app.repository.activeCalibrationId.value!=null && app.repository.activeCalibrationId.value!=oldId}
            val savedId=app.repository.activeCalibrationId.value!!
            runBlocking { assertEquals(90.0,app.repository.dao.calibration(savedId)!!.offset,0.0) }
            screenshot("sound-calibration-result")
            compose.onNodeWithText("停用当前校准").performScrollTo().performClick()
            compose.onNodeWithText("停用",substring=false).performClick()
            compose.waitUntil(5000){app.repository.activeCalibrationId.value==null}
            runBlocking { assertNotNull(app.repository.dao.calibration(savedId)) }
        } finally {
            runBlocking { oldId?.let { app.repository.dao.calibration(it) }?.let { app.repository.saveCalibration(it.copy(id=UUID.randomUUID().toString())) } }
            compose.runOnIdle { vm.calibration.reset() }
        }
    }

    @Test(timeout=90000) fun soundReportShowsRealStatisticsAndLabelsWaveformSeparately() {
        val session=SleepSession(startedAt=System.currentTimeMillis()-60000,endedAt=System.currentTimeMillis(),state=SessionState.FINISHED,effectiveMs=19500)
        fixtureIds+=session.id
        runBlocking {
            app.repository.dao.insertSession(session)
            app.repository.dao.insertLevels((0 until 20).map { SoundLevelFrame(session.id,session.startedAt+it*975L,dbfs=if(it==19)-20.0 else -40.0,aDbfs=if(it==19)-23.0 else -43.0) })
        }
        compose.waitUntil(10000){vm.data.value.sessions.any { it.id==session.id }}
        compose.runOnIdle { vm.openReport(session.id) }
        compose.waitUntil(10000){vm.data.value.levels.any { it.sessionId==session.id }}
        compose.onNodeWithText("夜间声音强度").performScrollTo().assertExists()
        compose.onNodeWithText("-32.3").assertExists()
        compose.onNodeWithText("-20.0").assertExists()
        compose.onNodeWithText("-40.0").assertExists()
        screenshot("sound-level-report")
    }

    @Test(timeout=30000) fun measurementConfigurationRemainsStableAcrossRecorderReopen() {
        fun captureKey():String = MeasurementInput(compose.activity).use { input ->
            assertTrue(input.read(ShortArray(LEVEL_WINDOW)))
            requireNotNull(input.configKey())
        }
        val first=captureKey()
        val second=captureKey()
        assertEquals("配置签名不能包含每次变化的音频会话 ID",first,second)
    }

    @Test(timeout=90000) fun playerKeepsItsOwnLevelsWhenAnotherNightIsOpened() {
        val first=seedPrototypeNight()
        val second=SleepSession(startedAt=System.currentTimeMillis()-60000,endedAt=System.currentTimeMillis(),state=SessionState.FINISHED)
        fixtureIds+=second.id
        val event=runBlocking {
            val event=app.repository.dao.events().first { it.sessionId==first.id }
            app.repository.dao.insertSession(second)
            app.repository.dao.insertLevels(listOf(
                SoundLevelFrame(first.id,event.startedAt,dbfs=-37.0,aDbfs=-40.0),
                SoundLevelFrame(second.id,second.startedAt,dbfs=-55.0,aDbfs=-58.0)))
            event
        }
        compose.waitUntil(10000){vm.data.value.sessions.any { it.id==second.id }}
        compose.runOnIdle { vm.openReport(first.id); vm.openPlayer(event) }
        compose.waitUntil(10000){vm.data.value.levels.any { it.sessionId==first.id }}
        compose.runOnIdle { vm.openReport(second.id); vm.playerExpanded=true }
        compose.waitUntil(10000){vm.data.value.levels.map { it.sessionId }.toSet()==setOf(first.id,second.id)}
        compose.onNodeWithText("事件时段声音 · dBFS").assertExists()
        compose.onAllNodesWithText("-37.0").onFirst().assertExists()
        compose.runOnIdle { vm.closePlayer() }
        compose.waitUntil(10000){vm.data.value.levels.all { it.sessionId==second.id }}
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
