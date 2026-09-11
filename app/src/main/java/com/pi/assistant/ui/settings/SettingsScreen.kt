package com.pi.assistant.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.MimoSpeech
import com.pi.assistant.data.prefs.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val wakeState by viewModel.wakeState.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var confirmClear by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setWakeEnabled(true)
        } else {
            Toast.makeText(context, "没有录音权限，唤醒开不了", Toast.LENGTH_SHORT).show()
        }
    }

    // 读 Wi-Fi 名用的权限：13+「附近设备」，12- 定位（ViewModel 里按版本给）
    var wifiNameGranted by remember { mutableStateOf(viewModel.hasWifiNamePermission) }
    val wifiNamePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        wifiNameGranted = granted
        if (!granted) {
            Toast.makeText(
                context,
                "没有权限就读不到 Wi-Fi 名，指定 Wi-Fi 条件不会满足",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本地历史？") },
            text = { Text("只删手机上的聊天记录，pi 那边的会话不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveAll() }) {
                        Icon(Icons.Filled.Save, contentDescription = "保存")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ------------------------------------------------------ 连接 pi
            SectionTitle("连接 pi")

            Field(
                value = draft.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                label = "pi 地址",
                placeholder = PiSettings.DEFAULT_BASE_URL,
                hint = "不写 http:// 会自动补上；IP 变了改这里即可，不用重装",
            )

            Field(
                value = draft.token,
                onValueChange = viewModel::updateToken,
                label = "token",
                hint = "pi 机器上：jq -r .token /root/.pi/agent/http-bridge.json" +
                    if (viewModel.encryptedBacked) "" else "（本机 Keystore 不可用，已降级为普通存储）",
                visualTransformation = if (draft.tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = {
                    IconButton(onClick = viewModel::toggleTokenVisible) {
                        Icon(
                            if (draft.tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (draft.tokenVisible) "隐藏" else "显示",
                        )
                    }
                },
            )

            Field(
                value = draft.timeoutSec,
                onValueChange = viewModel::updateTimeout,
                label = "超时（秒）",
                numeric = true,
                hint = "pi 一轮可能好几分钟。客户端实际超时 = 这个值 + 30s（${PiSettings.TIMEOUT_MIN}~${PiSettings.TIMEOUT_MAX}）",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { viewModel.saveAll() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Text("  保存")
                }
                OutlinedButton(
                    onClick = viewModel::probe,
                    enabled = !draft.probing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                    Text(if (draft.probing) "  探活中…" else "  探活")
                }
            }

            draft.probeText?.let { text ->
                NoticeCard(text = text, isError = draft.probeIsError)
            }

            // ------------------------------------------- 语音输入与朗读
            Spacer(Modifier.height(4.dp))
            SectionTitle("语音（小米 MiMo）")
            Text(
                "识别和朗读都走 MiMo，共用同一个 API Key —— 它俩挂在同一个 " +
                    "chat/completions 接口上，不是 OpenAI 那套语音端点。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field(
                value = draft.mimoToken,
                onValueChange = viewModel::updateMimoToken,
                label = "MiMo API Key",
                hint = "MiMo 控制台的 API Key，识别和朗读共用这一个",
                visualTransformation = PasswordVisualTransformation(),
            )

            Field(
                value = draft.mimoBaseUrl,
                onValueChange = viewModel::updateMimoBaseUrl,
                label = "接口地址",
                placeholder = MimoSpeech.DEFAULT_BASE_URL,
                hint = "只写域名会自动补 /v1；Token Plan 用户填订阅页给的区域地址",
            )

            // ------------------------------------------------------- 识别
            SubSectionTitle("识别")

            Field(
                value = draft.asrModel,
                onValueChange = viewModel::updateAsrModel,
                label = "识别模型",
                placeholder = SettingsDraft.DEFAULT_ASR_MODEL,
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "语种",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsDraft.ASR_LANGUAGES.forEach { lang ->
                        FilterChip(
                            selected = draft.asrLanguage == lang,
                            onClick = { viewModel.updateAsrLanguage(lang) },
                            label = { Text(lang) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "明确语种比自动检测更准；方言（粤语、四川话等）模型自己会处理。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = viewModel::testTranscribe,
                enabled = draft.asrConfigured && !draft.testingAsr && !draft.testingSpeech,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text(if (draft.testingAsr) "  录音识别中…" else "  试识别（录一句实测）")
            }

            // ------------------------------------------------------- 朗读
            SubSectionTitle("朗读")

            Field(
                value = draft.ttsModel,
                onValueChange = viewModel::updateTtsModel,
                label = "合成模型",
                placeholder = SettingsDraft.DEFAULT_TTS_MODEL,
            )

            Field(
                value = draft.ttsVoice,
                onValueChange = viewModel::updateTtsVoice,
                label = "音色",
                placeholder = "mimo_default",
                hint = "mimo_default、冰糖、茉莉、苏打、白桦、Mia、Chloe、Milo、Dean",
            )

            Field(
                value = draft.ttsStylePrompt,
                onValueChange = viewModel::updateTtsStylePrompt,
                label = "风格指令（可选）",
                placeholder = "温柔、稍慢的语调",
                hint = "MiMo 把要读的文本放在 assistant 消息里，这句作为 user 指令控制语气和情绪",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = draft.ttsSpeed,
                    onValueChange = viewModel::updateTtsSpeed,
                    label = "语速",
                    placeholder = "1.0",
                    keyboardType = KeyboardType.Decimal,
                    hint = "MiMo 没有数字语速参数，会折算成一句指令",
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = draft.ttsFormat,
                    onValueChange = viewModel::updateTtsFormat,
                    label = "音频格式",
                    placeholder = "wav",
                    hint = "仅非流式生效",
                    modifier = Modifier.weight(1f),
                )
            }

            SwitchRow(
                label = "流式播放（边合成边播）",
                hint = if (draft.ttsStream) {
                    "听到第一声不用等整段合完；格式固定 pcm16，上面的「音频格式」不生效"
                } else {
                    "整段合完再播：首字延迟高，但端点不认 stream 参数时可退回这条老路"
                },
                checked = draft.ttsStream,
                onToggle = { viewModel.toggleTtsStream() },
            )

            OutlinedButton(
                onClick = viewModel::testSpeak,
                enabled = draft.ttsConfigured && !draft.testingSpeech && !draft.testingAsr,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null)
                Text(if (draft.testingSpeech) "  合成中…" else "  试听（合成一句话）")
            }

            SwitchRow(
                label = "pi 的回复自动朗读",
                hint = "关掉的话，可以逐条点气泡右下角的小喇叭",
                checked = draft.autoSpeak,
                onToggle = { viewModel.toggleAutoSpeak() },
            )

            if (!draft.asrConfigured || !draft.ttsConfigured) {
                NoticeCard(
                    text = "语音还不能用：把上面的 MiMo API Key 填上再保存。",
                    isError = true,
                )
            }

            Button(onClick = { viewModel.saveAll() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text("  保存")
            }

            viewModel.micProblem?.let {
                NoticeCard(text = "端侧断句不可用：$it", isError = true)
            }

            // ----------------------------------------------------------- VAD
            Spacer(Modifier.height(4.dp))
            SectionTitle("说完自动停（VAD）")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = draft.vadThreshold,
                    onValueChange = viewModel::updateVadThreshold,
                    label = "灵敏度阈值",
                    placeholder = "0.5",
                    keyboardType = KeyboardType.Decimal,
                    hint = "吵就调高，听不清就调低",
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = draft.vadMinSilenceMs,
                    onValueChange = viewModel::updateVadSilence,
                    label = "静音判定 ms",
                    placeholder = "600",
                    numeric = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Field(
                value = draft.maxRecordSeconds,
                onValueChange = viewModel::updateMaxRecord,
                label = "单次最长录音（秒）",
                numeric = true,
                hint = "兜底用。说太久会自动掐断（3~60）",
            )

            // ----------------------------------------------------------- 唤醒
            Spacer(Modifier.height(4.dp))
            SectionTitle("后台唤醒")

            NoticeCard(
                text = "开启后麦克风将常驻采集，并显示一条常驻通知（可一键停止）。" +
                    "这个开关只由你手动打开，App 不会自启。",
                isError = false,
            )

            SwitchRow(
                label = "常驻聆听唤醒词",
                hint = when (wakeState) {
                    WakeState.OFF -> "当前：已关闭"
                    WakeState.RUNNING -> "当前：服务运行中"
                    WakeState.NOT_RUNNING -> "当前：配置是开的，但服务没在跑"
                },
                // 开关反映「配置意图」；服务实际有没有跑，看下面的状态提示
                checked = wakeState != WakeState.OFF,
                onToggle = { next ->
                    if (next) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.setWakeEnabled(false)
                    }
                },
            )

            if (wakeState == WakeState.NOT_RUNNING) {
                NoticeCard(
                    text = "唤醒服务现在没在运行 —— 多半被系统的省电策略杀掉了。" +
                        "刚打开开关的几秒内显示这句是正常的；如果一直这样，点下面重新拉起。",
                    isError = true,
                )
                OutlinedButton(
                    onClick = { viewModel.setWakeEnabled(true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新开启监听")
                }
            }

            viewModel.wakeProblem?.let {
                NoticeCard(text = "唤醒功能不可用：$it", isError = true)
            }

            // 唤醒词内置在打包词表里（assets/kws/keywords.txt），不可改
            Text(
                text = "唤醒词：${PiSettings.WAKE_WORD}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = draft.kwsScore,
                    onValueChange = viewModel::updateKwsScore,
                    label = "识别分数",
                    placeholder = "1.5",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = draft.kwsThreshold,
                    onValueChange = viewModel::updateKwsThreshold,
                    label = "触发阈值",
                    placeholder = "0.25",
                    keyboardType = KeyboardType.Decimal,
                    hint = "误唤醒多就调高",
                    modifier = Modifier.weight(1f),
                )
            }

            Field(
                value = draft.kwsThreads,
                onValueChange = viewModel::updateKwsThreads,
                label = "推理线程数",
                placeholder = "1",
                numeric = true,
                hint = "1 线程基本不掉点，省一半 CPU（1~4）",
            )

            SwitchRow(
                label = "仅充电时监听",
                checked = draft.wakeOnlyCharging,
                onToggle = { viewModel.toggleWakeCharging() },
            )
            SwitchRow(
                label = "仅连 Wi-Fi 时监听",
                checked = draft.wakeOnlyWifi,
                onToggle = { next ->
                    viewModel.toggleWakeWifi()
                    // 已填了指定 SSID 才需要权限（任意 Wi-Fi 只看传输层，不碰权限）
                    if (next && draft.wakeWifiSsid.isNotBlank() && !wifiNameGranted) {
                        wifiNamePermissionLauncher.launch(viewModel.wifiNamePermission)
                    }
                },
            )

            Field(
                value = draft.wakeWifiSsid,
                onValueChange = viewModel::updateWakeWifiSsid,
                label = "Wi-Fi 名称（SSID）",
                placeholder = "留空 = 任意 Wi-Fi",
                hint = "填了就只在这个 Wi-Fi 下监听，名字要和路由器上的一致（忽略大小写）。" +
                    "读 Wi-Fi 名需要权限：" +
                    if (Build.VERSION.SDK_INT >= 33) "Android 13+ 授权「附近设备」即可"
                    else "12 及以下要定位权限，且系统定位开关得开着",
            )

            if (draft.wakeOnlyWifi && draft.wakeWifiSsid.isNotBlank() && !wifiNameGranted) {
                OutlinedButton(
                    onClick = { wifiNamePermissionLauncher.launch(viewModel.wifiNamePermission) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (Build.VERSION.SDK_INT >= 33) "授权「附近设备」权限（读取 Wi-Fi 名）"
                        else "授权定位权限（读取 Wi-Fi 名）"
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = draft.wakeStartHour,
                    onValueChange = viewModel::updateWakeStart,
                    label = "起始小时",
                    placeholder = "-1",
                    numeric = true,
                    hint = "-1 = 不限时段",
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = draft.wakeEndHour,
                    onValueChange = viewModel::updateWakeEnd,
                    label = "结束小时",
                    placeholder = "-1",
                    numeric = true,
                    hint = "支持跨零点，如 22→7",
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedButton(
                onClick = {
                    if (!viewModel.requestIgnoreBatteryOptimizations()) {
                        Toast.makeText(context, "这台设备不支持直接跳转，请手动到系统设置里关掉省电限制", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("把本 App 加入电池优化白名单")
            }
            Text(
                "厂商保活只能尽力而为：小米要开自启动、华为要在启动管理里允许后台、OPPO/vivo 要关深度睡眠。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ----------------------------------------------------------- 外观
            Spacer(Modifier.height(4.dp))
            SectionTitle("外观")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { viewModel.updateTheme(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }

            // ----------------------------------------------------------- 隐私
            Spacer(Modifier.height(4.dp))
            SectionTitle("隐私")

            SwitchRow(
                label = "隐藏最近任务卡片",
                hint = "开启后，系统最近任务里不再显示本应用的预览卡片",
                checked = draft.hideRecents,
                onToggle = { viewModel.toggleHideRecents() },
            )

            // ----------------------------------------------------------- 数据
            Spacer(Modifier.height(4.dp))
            SectionTitle("数据")
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Text("  清空本地聊天历史")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "M1 文本对话 · M2 语音输入与朗读 · M3 后台唤醒，都在这一个 App 里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 大节里的小节标题 —— 语音那块要分「识别 / 朗读」两半，用这个区分层级。 */
@Composable
private fun SubSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NoticeCard(text: String, isError: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    hint: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    numeric: Boolean = false,
    keyboardType: KeyboardType? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        supportingText = if (hint != null) ({ Text(hint) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType ?: if (numeric) KeyboardType.Number else KeyboardType.Text
        ),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
