package com.miansheng.sleep.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import com.miansheng.sleep.data.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

fun clock(time: Long, zone: String = ZoneId.systemDefault().id): String = Instant.ofEpochMilli(time).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm"))
fun nightTitle(session: SleepSession): String = session.date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)) + " 夜间"
fun sessionRange(session: SleepSession): String {
    val end = session.endedAt ?: System.currentTimeMillis()
    val endDate = Instant.ofEpochMilli(end).atZone(ZoneId.of(session.zoneId)).toLocalDate()
    return "${clock(session.startedAt, session.zoneId)} – ${if (endDate != session.date) "次日 " else ""}${clock(end, session.zoneId)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepApp(vm: SleepViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val retention by vm.retention.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.runPrecheck()
        else vm.screen = Screen.DENIED
    }
    fun requestPermission() {
        vm.sheet = null
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }
    val startFlow: () -> Unit = {
        if (data.live.sessionId != null && data.live.state != SessionState.FINISHED) vm.screen = Screen.MONITOR
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.runPrecheck()
        else vm.sheet = "permission"
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && vm.screen == Screen.DENIED && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.runPrecheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val isRoot = vm.screen in listOf(Screen.HOME, Screen.HISTORY, Screen.TRENDS, Screen.SETTINGS)
    BackHandler(vm.sheet != null || vm.playerExpanded || !isRoot && vm.screen != Screen.ONBOARD) { vm.back() }
    val background = if (vm.screen == Screen.MONITOR) SleepColors.Night else SleepColors.Background
    val selected = data.sessions.find { it.id == vm.selectedSessionId }
    val playerEvent = data.events.find { it.id == vm.playerEventId }
    val share: (SoundEvent) -> Unit = { event ->
        try {
            if (event.available && File(event.clipPath!!).isFile) {
                vm.pausePlayback()
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(event.clipPath))
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享这段录音"))
            } else vm.snack(clipMessage(event))
        } catch (_: Exception) { vm.snack("暂时无法分享这段录音") }
    }
    Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (!vm.loaded) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = SleepColors.Primary)
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (vm.screen) {
                    Screen.ONBOARD -> OnboardScreen(vm::finishOnboard)
                    Screen.HOME -> HomeScreen(data, startFlow, vm)
                    Screen.PRECHECK -> PrecheckScreen(vm)
                    Screen.DENIED -> DeniedScreen(vm) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                    Screen.MONITOR -> MonitorScreen(data.live, vm)
                    Screen.GENERATING -> GeneratingScreen()
                    Screen.REPORT -> if (selected != null) ReportScreen(selected, data, vm) else GeneratingScreen()
                    Screen.RECORDINGS -> if (selected != null) RecordingsScreen(selected, data, vm) else EmptyState("记录不可用", "这次监测记录可能已被删除")
                    Screen.HISTORY -> HistoryScreen(data, vm)
                    Screen.TRENDS -> TrendsScreen(data, vm)
                    Screen.SETTINGS -> SettingsScreen(data, retention, vm)
                    Screen.HELP -> HelpScreen(vm)
                }
                if (playerEvent != null && !vm.playerExpanded && vm.screen != Screen.MONITOR) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { MiniPlayer(playerEvent, vm) }
                }
            }
            if (isRoot) BottomNavigation(vm.screen, vm::tab)
        }
        if (vm.sheet == null && !vm.playerExpanded && vm.snackbar != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = if (isRoot) 92.dp else 16.dp)) { FeedbackBar(vm) }
        }
    }
    if (vm.sheet != null || vm.playerExpanded && playerEvent != null) {
        ModalBottomSheet(onDismissRequest = { if (vm.sheet != null) vm.sheet = null else vm.playerExpanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = if (vm.sheet == "end") Color(0xFF111824) else SleepColors.Sheet,
            contentColor = SleepColors.Text, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 16.dp).size(32.dp, 4.dp).clip(CircleShape).background(Color(0xFF4A5568))) }) {
            SheetContents(vm, data, selected, playerEvent, retention, ::requestPermission, share)
            if (vm.snackbar != null) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) { FeedbackBar(vm) }
        }
    }
}

@Composable
private fun BottomNavigation(selected: Screen, navigate: (Screen) -> Unit) {
    Row(Modifier.fillMaxWidth().height(80.dp).background(SleepColors.Nav)) {
        listOf(Triple(Screen.HOME, "首页", "house"), Triple(Screen.HISTORY, "记录", "calendar-days"),
            Triple(Screen.TRENDS, "趋势", "chart-column"), Triple(Screen.SETTINGS, "设置", "settings")).forEach { (screen, title, icon) ->
            val active = selected == screen
            Column(Modifier.weight(1f).fillMaxHeight().clickable { navigate(screen) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(64.dp, 32.dp).clip(CircleShape).background(if (active) SleepColors.Primary.copy(alpha = .18f) else Color.Transparent), contentAlignment = Alignment.Center) {
                    Glyph(icon, if (active) SleepColors.PrimaryText else SleepColors.Secondary)
                }
                Spacer(Modifier.height(4.dp))
                Txt(title, 12, if (active) SleepColors.PrimaryText else SleepColors.Secondary, if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun FeedbackBar(vm: SleepViewModel) {
    val text = vm.snackbar ?: return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9E4DA))
        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Txt(text, 14, SleepColors.Card, modifier = Modifier.weight(1f))
        vm.undo?.let { undo -> TextButton(onClick = undo) { Txt("撤销", 14, Color(0xFF2F7D72), FontWeight.SemiBold) } }
    }
}
