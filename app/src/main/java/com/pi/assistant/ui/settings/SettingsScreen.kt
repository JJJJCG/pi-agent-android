package com.pi.assistant.ui.settings

import android.Manifest
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
import com.pi.assistant.data.prefs.AsrPreset
import com.pi.assistant.data.prefs.AsrProtocol
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.ThemeMode
import com.pi.assistant.data.prefs.TtsPreset
import com.pi.assistant.data.prefs.TtsProtocol

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
            SectionTitle("语音输入与朗读")
            Text(
                "识别和朗读各自独立，协议和服务商都能不同 —— 默认识别用百炼 Fun-ASR、朗读用小米 MiMo。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------------------------------------------------- 识别（ASR）
            SubSectionTitle("识别（ASR）")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AsrPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = draft.asrPreset == preset,
                        onClick = { viewModel.applyAsrPreset(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }

            ProtocolRow(
                label = "请求协议",
                options = AsrProtocol.entries.map { it to it.label() },
                selected = draft.asrProtocol,
                onSelect = viewModel::updateAsrProtocol,
                hint = draft.asrProtocol.hint(),
            )

            Field(
                value = draft.asrBaseUrl,
                onValueChange = viewModel::updateAsrBaseUrl,
                label = "识别端点地址",
                placeholder = "https://xxx.cn-beijing.maas.aliyuncs.com",
                hint = if (draft.asrProtocol == AsrProtocol.BAILIAN_FUN_ASR) {
                    "把 {WorkspaceId} 换成控制台上的那个；只填到域名，路径由 App 补"
                } else {
                    "按 OpenAI 约定要带 /v1；只写域名会自动补上"
                },
            )

            if (draft.asrBaseUrlLooksUnfilled) {
                NoticeCard(
                    text = "地址里的 {WorkspaceId} 还是占位符，这样请求发不出去。" +
                        "去百炼控制台复制你的 Workspace ID 换上。",
                    isError = true,
                )
            }

            Field(
                value = draft.asrToken,
                onValueChange = viewModel::updateAsrToken,
                label = "识别端点 token",
                hint = if (draft.asrProtocol == AsrProtocol.BAILIAN_FUN_ASR) {
                    "百炼这边是 DASHSCOPE_API_KEY，注意北京和新加坡的 key 不通用"
                } else {
                    "留空表示该端点不校验鉴权"
                },
                visualTransformation = PasswordVisualTransformation(),
            )

            Field(
                value = draft.asrModel,
                onValueChange = viewModel::updateAsrModel,
                label = "ASR 模型",
                placeholder = "fun-asr-flash-2026-06-15",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = draft.asrLanguage,
                    onValueChange = viewModel::updateAsrLanguage,
                    label = "识别语言",
                    placeholder = "zh",
                    hint = if (draft.asrProtocol == AsrProtocol.BAILIAN_FUN_ASR) {
                        "百炼只认第一个值"
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = draft.asrPrompt,
                    onValueChange = viewModel::updateAsrPrompt,
                    label = "识别提示词",
                    placeholder = "可选",
                    enabled = draft.asrProtocol == AsrProtocol.OPENAI,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (draft.asrProtocol == AsrProtocol.OPENAI) {
                    "提示词里塞几个专有名词，能明显提升识别率。"
                } else {
                    "提示词只在 OpenAI 协议下生效 —— 百炼的上下文走消息列表，格式对不上，" +
                        "所以这边不发它。热词请用百炼控制台预编译的词汇表。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = viewModel::testTranscribe,
                enabled = draft.asrConfigured && !draft.testingAsr && !draft.testingSpeech,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text(if (draft.testingAsr) "  录音识别中…" else "  试识别（录一句实测）")
            }

            if (!draft.asrConfigured) {
                Text(
                    "地址和模型都填上才能试识别。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------------------------------------------- 朗读（TTS）
            SubSectionTitle("朗读（TTS）")

            if (draft.ttsShareAvailable) {
                SwitchRow(
                    label = "与识别使用同一服务商",
                    hint = "关掉就能给朗读单独填另一家端点",
                    checked = draft.ttsShareAsr,
                    onToggle = { viewModel.toggleTtsShareAsr() },
                )
            }

            if (draft.ttsShareAsr && draft.ttsShareAvailable) {
                Text(
                    text = if (draft.asrBaseUrl.isBlank()) {
                        "朗读会跟着识别走，但识别的地址还没填。"
                    } else {
                        "朗读将走 ${draft.asrBaseUrl}，token 也共用。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TtsPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = draft.ttsPreset == preset,
                            onClick = { viewModel.applyTtsPreset(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }

                ProtocolRow(
                    label = "请求协议",
                    options = TtsProtocol.entries.map { it to it.label() },
                    selected = draft.ttsProtocol,
                    onSelect = viewModel::updateTtsProtocol,
                    hint = draft.ttsProtocol.hint(),
                )

                Field(
                    value = draft.ttsBaseUrl,
                    onValueChange = viewModel::updateTtsBaseUrl,
                    label = "朗读端点地址",
                    placeholder = "https://api.xiaomimimo.com/v1",
                    hint = if (draft.ttsProtocol == TtsProtocol.MIMO_CHAT) {
                        "MiMo 的地址得带 /v1，只写域名会自动补上"
                    } else {
                        "和识别填不一样就是两家服务商"
                    },
                )

                Field(
                    value = draft.ttsToken,
                    onValueChange = viewModel::updateTtsToken,
                    label = "朗读端点 token",
                    hint = if (draft.ttsProtocol == TtsProtocol.MIMO_CHAT) {
                        "MiMo 控制台的 API Key"
                    } else {
                        "留空表示该端点不校验鉴权"
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            Field(
                value = draft.ttsModel,
                onValueChange = viewModel::updateTtsModel,
                label = "TTS 模型",
                placeholder = "mimo-v2.5-tts / tts-1 / kokoro",
            )

            Field(
                value = draft.ttsVoice,
                onValueChange = viewModel::updateTtsVoice,
                label = "音色",
                placeholder = "mimo_default",
                hint = if (draft.ttsProtocol == TtsProtocol.MIMO_CHAT) {
                    "可填 mimo_default、冰糖、茉莉、苏打、白桦、Mia、Chloe、Milo、Dean"
                } else {
                    "如 alloy、nova；自建端点看它自己的音色表"
                },
            )

            if (draft.ttsProtocol == TtsProtocol.MIMO_CHAT) {
                Field(
                    value = draft.ttsStylePrompt,
                    onValueChange = viewModel::updateTtsStylePrompt,
                    label = "风格指令（可选）",
                    placeholder = "温柔、稍慢的语调",
                    hint = "MiMo 把合成文本放在 assistant 消息里，这句作为 user 指令控制语气/情绪/方言",
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = draft.ttsSpeed,
                    onValueChange = viewModel::updateTtsSpeed,
                    label = "语速",
                    placeholder = "1.0",
                    keyboardType = KeyboardType.Decimal,
                    hint = if (draft.ttsProtocol == TtsProtocol.MIMO_CHAT) {
                        "MiMo 没有数字语速参数，会折算成一句自然语言指令"
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = draft.ttsFormat,
                    onValueChange = viewModel::updateTtsFormat,
                    label = "音频格式",
                    placeholder = "wav",
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (draft.ttsProtocol == TtsProtocol.MIMO_CHAT) {
                    "格式要和端点实际返回的一致。MiMo 非流式用 wav 最省事 —— 拿到的就是完整文件，不用再解码。"
                } else {
                    "格式要和端点实际返回的一致，否则播放器解不出来。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (draft.autoSpeak && !draft.ttsConfigured) {
                NoticeCard(
                    text = "自动朗读还不会生效：朗读端点的地址或模型没填全。",
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

            Field(
                value = draft.wakeKeyword,
                onValueChange = viewModel::updateWakeKeyword,
                label = "唤醒词",
                hint = "多个用逗号隔开。改完要重跑 tools 里的生成脚本，见 README",
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

            SwitchRow(
                label = "仅充电时监听",
                checked = draft.wakeOnlyCharging,
                onToggle = { viewModel.toggleWakeCharging() },
            )
            SwitchRow(
                label = "仅连 Wi-Fi 时监听",
                checked = draft.wakeOnlyWifi,
                onToggle = { viewModel.toggleWakeWifi() },
            )

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
    enabled: Boolean = true,
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
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType ?: if (numeric) KeyboardType.Number else KeyboardType.Text
        ),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 「协议」选择行。
 *
 * 常驻显示而不是藏在预设里：协议决定请求长什么样（multipart / base64 JSON / chat 补全），
 * 排查问题时要能一眼看见现在发的是哪种，而不是去猜某个预设背后是什么。
 */
@Composable
private fun <T> ProtocolRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    hint: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AsrProtocol.label(): String = when (this) {
    AsrProtocol.OPENAI -> "OpenAI 兼容"
    AsrProtocol.BAILIAN_FUN_ASR -> "百炼 DashScope"
}

private fun AsrProtocol.hint(): String = when (this) {
    AsrProtocol.OPENAI -> "multipart 上传音频到 /audio/transcriptions"
    AsrProtocol.BAILIAN_FUN_ASR -> "音频以 base64 塞进 JSON，走 DashScope 的 multimodal-generation"
}

private fun TtsProtocol.label(): String = when (this) {
    TtsProtocol.OPENAI -> "OpenAI 兼容"
    TtsProtocol.MIMO_CHAT -> "MiMo 聊天补全"
}

private fun TtsProtocol.hint(): String = when (this) {
    TtsProtocol.OPENAI -> "POST /audio/speech，响应体就是音频字节"
    TtsProtocol.MIMO_CHAT -> "走 chat/completions，文本放 assistant 消息，音频 base64 在响应里"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
