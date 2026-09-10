package com.pi.assistant.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pi.assistant.data.pi.CallRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 排障页。后期 100% 用得上，所以 M1 就做掉：
 * 原始 /v1/status JSON、最近一次请求的判定与耗时、完整错误体。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val rawStatus by viewModel.rawStatus.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val lastCall by viewModel.lastCall.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val voiceStage by viewModel.voiceStage.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceError.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val wakeCount by viewModel.wakeCount.collectAsStateWithLifecycle()
    val lastWake by viewModel.lastWake.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
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
            OutlinedButton(
                onClick = viewModel::checkHealth,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.MonitorHeart, contentDescription = null)
                Text("  探活 /healthz")
            }
            health?.let { MonoCard(title = "探活结果", body = it) }

            MonoCard(title = "端侧语音资源自检", body = assets)

            MonoCard(
                title = "语音链路",
                body = buildString {
                    appendLine("唤醒服务   ${if (serviceRunning) "运行中" else "已停止"}")
                    appendLine("当前环节   ${voiceStage.label}")
                    appendLine("唤醒次数   $wakeCount")
                    appendLine("上次唤醒   ${lastWake ?: "-"}")
                    voiceError?.let { appendLine("最近错误   $it") }
                }.trim(),
                isError = voiceError != null,
            )

            MonoCard(
                title = "当前配置",
                body = buildString {
                    appendLine("baseUrl       = ${config.baseUrl}")
                    appendLine("token         = ${maskToken(config.token)}")
                    appendLine("timeoutSec    = ${config.timeoutSec}（客户端 ${config.timeoutSec + 30}s）")
                    appendLine("themeMode     = ${config.themeMode}")
                }.trim(),
            )

            MonoCard(
                title = "最近一次 /v1/chat",
                body = lastCall?.render() ?: "本次启动以来还没有发过请求",
                isError = lastCall?.resultLabel in setOf("Failed", "AuthError"),
            )

            MonoCard(
                title = "GET /v1/status（原始 JSON）",
                body = rawStatus ?: "加载中…",
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：把 timeout 调成 1 秒就能稳定复现 504，用来验证「不重发 + 查 busy」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonoCard(
    title: String,
    body: String,
    isError: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun CallRecord.render(): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    return buildString {
        appendLine("时间        $time")
        appendLine("HTTP        ${httpCode ?: "-"}")
        appendLine("code        ${code ?: "-"}")
        appendLine("判定        $resultLabel")
        appendLine("耗时        ${ms}ms")
        note?.let { appendLine("说明        $it") }
        errorBody?.let { appendLine("错误体      $it") }
    }.trim()
}

private fun maskToken(token: String): String = when {
    token.isBlank() -> "(空 —— pi 可能关了鉴权)"
    token.length <= 8 -> "****"
    else -> "${token.take(4)}…${token.takeLast(2)}（共 ${token.length} 位）"
}
