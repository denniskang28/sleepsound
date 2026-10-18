package com.miansheng.sleep.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import com.miansheng.sleep.domain.fitCalibration
import kotlin.math.ceil

@Composable
fun CalibrationScreen(data: AppData, vm: SleepViewModel) {
    val state by vm.calibration.state.collectAsStateWithLifecycle()
    val activeId by vm.activeCalibrationId.collectAsStateWithLifecycle()
    val active = data.calibrations.find { it.id == activeId }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var reference by rememberSaveable { mutableStateOf("") }
    var meterName by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) vm.calibration.capture()
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.calibration.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.calibration.cancel() }
    }
    val fit = remember(state.points) { runCatching { fitCalibration(state.points) } }
    Column(Modifier.fillMaxSize()) {
        PageTitle("声音校准", vm::back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SleepCard {
                Txt(if (active == null) "未启用校准" else "已保存校准", 20, weight=FontWeight.SemiBold)
                if (active != null) {
                    Txt("${active.deviceLabel}\n参考仪器：${active.referenceMeter}",13,SleepColors.Secondary)
                    Txt("验证范围 ${active.minimum.toInt()}–${active.maximum.toInt()} dBA · 最大比对偏差 ${displayLevel(active.maxError,null)} dB",13,SleepColors.PrimaryText)
                    Txt("使用时会核对录音配置，历史记录保留当时的校准。",11,SleepColors.Muted)
                    TextButton(onClick={ confirmReset=true }, enabled=!state.capturing && !state.saving) { Txt("停用当前校准",13,SleepColors.Danger) }
                } else Txt("监测显示相对电平 dBFS；完成三组比对后才能显示估计 dBA。",13,SleepColors.Secondary)
            }
            SleepCard {
                Txt("1. 准备与摆放",16,weight=FontWeight.SemiBold)
                Txt("准备有校准记录、量程覆盖目标声音的参考声级计，以及另一台设备连接的音箱。播放稳定粉红噪声，两个麦克风靠近、互不遮挡，距音箱大致相同；保持声源和位置不变。",13,SleepColors.Secondary)
                Txt("参考仪器使用 A 计权 LAeq，与本次采集同时开始、结束。手机会先预热约 1 秒，再保留约 30 秒数据；全程保持声音稳定。不要用音频文件名或另一台未校准手机的数值作为参考。",13,SleepColors.Secondary)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(confirmed,{ confirmed=it },enabled=!state.capturing)
                    Txt("已确认参考仪器使用 A 计权 LAeq",13,modifier=Modifier.weight(1f))
                }
            }
            SleepCard {
                Txt("2. 同步测量并填写参考值",16,weight=FontWeight.SemiBold)
                Txt("至少三组，相邻参考值差 ≥5 dB，总跨度 ≥15 dB。参考值范围 25–90 dBA；请覆盖实际卧室音量，仪器测不到的低声级不能用于校准。",13,SleepColors.Secondary)
                if (state.capturing) {
                    Txt(if(state.capturedMs==0L) "麦克风预热中，请同步启动参考仪器" else "正在采集 · 还需约 ${ceil((30000-state.capturedMs).coerceAtLeast(0)/1000.0).toInt()} 秒",15,SleepColors.PrimaryText)
                    LinearProgressIndicator(progress={ (state.capturedMs/30000f).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
                    Txt("当前 A 计权数字电平 ${displayLevel(state.currentDbfs,null)} dBFS(A)，尚未换算环境声级",11,SleepColors.Muted)
                    ActionButton("取消本组采集","x",primary=false,onClick=vm.calibration::cancel)
                } else if (state.pending != null) {
                    Txt("本组采集完成，请停止参考仪器并填写同段 LAeq",14,SleepColors.PrimaryText)
                    OutlinedTextField(reference,{ reference=it },label={ Txt("参考仪器 LAeq（dBA）",13) },
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                    ActionButton("保存本组参考值","check") { vm.calibration.addReference(reference); if(vm.calibration.state.value.pending==null)reference="" }
                    ActionButton("重测本组","rotate-ccw",primary=false) { reference=""; vm.calibration.capture() }
                } else if (!state.saved) {
                    ActionButton("开始第 ${state.points.size+1} 组采集","mic",enabled=confirmed && !state.saving) {
                        reference=""
                        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) vm.calibration.capture()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                if(permissionDenied) {
                    Hint("需要麦克风权限才能校准。",color=SleepColors.Danger)
                    TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}) { Txt("前往权限设置",13,SleepColors.PrimaryText) }
                }
                state.points.forEachIndexed { i,p ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("第 ${i+1} 组 · 参考 ${displayLevel(p.reference,null)} dBA",14)
                            Txt("采集 ${displayLevel(p.measured,null)} dBFS(A) · 波动 ${displayLevel(p.spread,null)} dB",11,SleepColors.Secondary)
                        }
                        if(!state.saved) IconTap("x","删除第 ${i+1} 组") { if(!state.capturing)vm.calibration.removePoint(i) }
                    }
                }
                state.error?.let { Hint(it,"triangle-alert",SleepColors.Danger) }
            }
            if(state.points.isNotEmpty()) SleepCard {
                Txt("3. 检查与启用",16,weight=FontWeight.SemiBold)
                fit.getOrNull()?.let {
                    Txt("比对一致性通过 · 修正 ${displayLevel(it.offset,null)} dB\n验证范围 ${it.minimum.toInt()}–${it.maximum.toInt()} dBA\n各组最大偏差 ${displayLevel(it.maxError,null)} dB",14,SleepColors.PrimaryText)
                } ?: Txt(fit.exceptionOrNull()?.message ?: "请继续完成测量",13,SleepColors.Secondary)
                if(!state.saved) {
                    OutlinedTextField(meterName,{meterName=it.take(100)},label={Txt("参考声级计型号／编号",13)},singleLine=true,modifier=Modifier.fillMaxWidth())
                    ActionButton(if(state.saving)"正在保存校准" else "保存并启用校准","check",enabled=fit.isSuccess && !state.capturing && !state.saving && state.pending==null && meterName.isNotBlank()) {
                        vm.calibration.save(meterName) { vm.snack("校准已保存，新监测将核对并使用此配置") }
                    }
                } else Hint("校准已保存。下一次监测自动核对麦克风和录音配置。","circle-check",SleepColors.PrimaryText)
                if(!state.capturing && !state.saving) ActionButton("清空本轮并重新测量","rotate-ccw",primary=false) { vm.calibration.reset(); reference="" }
            }
            Hint("比对阈值是产品一致性检查，不是精度认证。16 kHz 采音仅覆盖 8 kHz 以下，单一修正不能消除频响误差。超出验证范围时显示范围提示，换设备、麦克风或系统录音配置后请重新校准。")
            Spacer(Modifier.height(20.dp))
        }
    }
    if(confirmReset) AlertDialog(onDismissRequest={confirmReset=false},title={Txt("停用校准？",20)},text={Txt("新监测将使用 dBFS。已保存报告仍保留当时的校准数据。",14)},
        confirmButton={TextButton(onClick={vm.repo.resetCalibration();confirmReset=false}){Txt("停用",14,SleepColors.Danger)}},
        dismissButton={TextButton(onClick={confirmReset=false}){Txt("取消",14)}})
}
