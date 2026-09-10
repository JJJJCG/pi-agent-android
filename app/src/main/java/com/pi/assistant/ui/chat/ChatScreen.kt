package com.pi.assistant.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.pi.assistant.data.local.MessageEntity
import com.pi.assistant.data.pi.PiStatus
import com.pi.assistant.ui.components.MarkdownText
import com.pi.assistant.voice.VoiceStage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val sending = phase is SendPhase.Waiting
    val clipboard = LocalClipboardManager.current

    val voiceStage by viewModel.voiceStage.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micProblem = viewModel.micUnavailableReason
    val recording = voiceStage == VoiceStage.RECORDING || voiceStage == VoiceStage.TRANSCRIBING

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceInput()
        } else {
            Toast.makeText(context, "没有录音权限，麦克风用不了", Toast.LENGTH_SHORT).show()
        }
    }

    val onMicClick: () -> Unit = {
        when {
            micProblem != null ->
                Toast.makeText(context, micProblem, Toast.LENGTH_LONG).show()

            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> viewModel.startVoiceInput()

            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("pi 对话") },
                actions = {
                    StatusChip(status = status, onRefresh = viewModel::refreshStatus)
                    IconButton(onClick = onOpenDebug) {
                        Icon(Icons.Filled.BugReport, contentDescription = "调试")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                PhaseBanner(phase = phase, onDismiss = viewModel::dismissNotice)
                VoiceBanner(
                    stage = voiceStage,
                    error = voiceError,
                    onDismissError = viewModel::dismissVoiceError,
                )
                Composer(
                    value = input,
                    onValueChange = viewModel::onInputChange,
                    sending = sending,
                    onSend = viewModel::send,
                    micEnabled = micProblem == null && !sending,
                    recording = recording,
                    onMicClick = onMicClick,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (messages.isEmpty()) {
                EmptyHint(modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = messages, key = { it.id }) { message ->
                    Bubble(
                        message = message,
                        onCopy = { clipboard.setText(AnnotatedString(message.text)) },
                        onResend = { viewModel.resend(message) },
                        resendEnabled = !sending,
                        speaking = voiceStage == VoiceStage.SPEAKING,
                        onSpeak = { viewModel.speak(message.text) },
                        onStopSpeak = viewModel::stopSpeaking,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 子组件

@Composable
private fun StatusChip(status: PiStatus?, onRefresh: () -> Unit) {
    val (label, tint) = when {
        status == null -> "未连接" to MaterialTheme.colorScheme.error
        status.busy -> "忙碌" to Color(0xFFB26A00)
        else -> "空闲" to Color(0xFF2E9E86)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable(onClick = onRefresh),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新状态",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PhaseBanner(phase: SendPhase, onDismiss: () -> Unit) {
    val (text, bg, fg, dismissible) = when (phase) {
        is SendPhase.Idle -> return
        is SendPhase.Waiting -> Quad(
            "pi 思考中… 已等待 ${phase.seconds}s（一轮可能几分钟，中途无法取消）",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            false,
        )
        is SendPhase.StillRunning -> Quad(
            "⚠ ${phase.hint}",
            Color(0x33B26A00),
            MaterialTheme.colorScheme.onSurface,
            true,
        )
        is SendPhase.Notice -> Quad(
            (if (phase.isError) "⚠ " else "ⓘ ") + phase.message,
            if (phase.isError) Color(0x22C0392B) else MaterialTheme.colorScheme.surfaceVariant,
            if (phase.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            true,
        )
    }
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (dismissible) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

private data class Quad(
    val a: String,
    val b: Color,
    val c: Color,
    val d: Boolean,
)

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    micEnabled: Boolean,
    recording: Boolean,
    onMicClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        FilledIconButton(
            onClick = onMicClick,
            // 录音中不给再点：VAD 这一轮没有取消接口，按了也没用，不如禁掉
            enabled = micEnabled && !recording,
        ) {
            Icon(
                imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (recording) "正在录音" else "语音输入",
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = !sending,
            placeholder = {
                Text(
                    when {
                        sending -> "pi 正在思考，先别急…"
                        recording -> "在听你说，说完自动停"
                        else -> "说点什么"
                    }
                )
            },
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!sending) onSend() }),
            shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = !sending && value.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
        }
    }
}

/** 语音链路的状态条：录音/识别/朗读/出错都走这里，跟发送状态分开显示。 */
@Composable
private fun VoiceBanner(
    stage: VoiceStage,
    error: String?,
    onDismissError: () -> Unit,
) {
    val text = error ?: when (stage) {
        VoiceStage.RECORDING -> "🎙 在听你说，说完自动停"
        VoiceStage.TRANSCRIBING -> "识别中…"
        VoiceStage.ASKING -> "正在问 pi…"
        VoiceStage.SPEAKING -> "🔊 朗读回复中"
        VoiceStage.WAITING_WAKE -> null
        VoiceStage.IDLE, VoiceStage.ERROR -> null
    } ?: return

    val isError = error != null
    Surface(
        color = if (isError) Color(0x22C0392B) else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = text,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (isError) {
                TextButton(onClick = onDismissError) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "连接你的 pi",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "先在设置页填好 pi 地址和 token，\n然后在这里发第一句话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: MessageEntity,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    resendEnabled: Boolean,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onStopSpeak: () -> Unit,
) {
    val isUser = message.role == MessageEntity.ROLE_USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = bubbleColor,
            tonalElevation = if (isUser) 0.dp else 2.dp,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .combinedClickable(onClick = {}, onLongClick = onCopy),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isUser) {
                    Text(text = message.text, color = contentColor)
                } else {
                    MarkdownText(markdown = message.text, textColor = contentColor)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = message.footnote().orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { if (speaking) onStopSpeak() else onSpeak() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = if (speaking) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                                contentDescription = if (speaking) "停止朗读" else "朗读这条",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (message.failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "未送达",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onResend, enabled = resendEnabled) { Text("重发") }
            }
        }
    }
}

private fun MessageEntity.footnote(): String? {
    val parts = buildList {
        tools?.let { add("$it 次工具调用") }
        ms?.let { add("耗时 ${formatMs(it)}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatMs(ms: Long): String =
    if (ms >= 1000) String.format(Locale.US, "%.1fs", ms / 1000.0) else "${ms}ms"
