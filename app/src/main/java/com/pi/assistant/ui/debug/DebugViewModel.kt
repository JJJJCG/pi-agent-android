package com.pi.assistant.ui.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pi.assistant.audio.KwsEngine
import com.pi.assistant.audio.SherpaNative
import com.pi.assistant.audio.VadRecorder
import com.pi.assistant.audio.VoiceAssets
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.data.pi.CallRecord
import com.pi.assistant.data.pi.PiRepository
import com.pi.assistant.data.pi.ProbeResult
import com.pi.assistant.voice.VoiceBus
import com.pi.assistant.voice.VoiceStage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PiRepository,
    private val settings: SettingsStore,
    private val vadRecorder: VadRecorder,
    private val kwsEngine: KwsEngine,
    private val bus: VoiceBus,
) : ViewModel() {

    private val _rawStatus = MutableStateFlow<String?>(null)
    val rawStatus: StateFlow<String?> = _rawStatus.asStateFlow()

    private val _health = MutableStateFlow<String?>(null)
    val health: StateFlow<String?> = _health.asStateFlow()

    /** 资源自检结果。assets 探测要开文件，放主线程做不合适，所以预先算好。 */
    private val _assets = MutableStateFlow("检测中…")
    val assets: StateFlow<String> = _assets.asStateFlow()

    val lastCall: StateFlow<CallRecord?> = repository.lastCall
    val config: StateFlow<PiSettings> = settings.state

    // ---- 语音 / 唤醒现场，排障时一眼看出卡在哪一环

    val voiceStage: StateFlow<VoiceStage> = bus.stage
    val voiceError: StateFlow<String?> = bus.error
    val serviceRunning: StateFlow<Boolean> = bus.serviceRunning
    val wakeCount: StateFlow<Int> = bus.wakeCount
    val lastWake: StateFlow<String?> = bus.lastWake

    val vadProblem: String? get() = vadRecorder.unavailableReason()
    val kwsProblem: String? get() = kwsEngine.unavailableReason()
    val encryptedBacked: Boolean get() = settings.encryptedBacked

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _rawStatus.value = "加载中…"
            _rawStatus.value = repository.statusRawJson()
            // assets 探测走 IO 线程，别在主线程开文件
            _assets.value = withContext(Dispatchers.IO) { buildAssetsReport() }
        }
    }

    fun checkHealth() {
        viewModelScope.launch {
            _health.value = when (val result = repository.probe()) {
                is ProbeResult.Ok -> "✓ ${result.text} · ${result.ms}ms"
                is ProbeResult.Fail -> "✗ ${result.message}"
            }
        }
    }

    /**
     * 资源自检。端侧语音出问题时，九成是这里缺东西，
     * 把解析到的实际路径摊开看，比猜快得多。
     */
    private fun buildAssetsReport(): String {
        val paths = VoiceAssets.resolveKws(context)
        return buildString {
            appendLine("native 库        ${if (SherpaNative.available) "已加载 ✓" else "缺失 ✗ ${SherpaNative.reason}"}")
            appendLine("silero_vad.onnx  ${if (VoiceAssets.vadReady(context)) "有 ✓" else "缺 ✗"}")
            if (paths == null) {
                appendLine("关键词模型       缺 ✗ ${VoiceAssets.missingForKws(context).joinToString("、")}")
            } else {
                appendLine("关键词模型       已解析 ✓")
                appendLine("  encoder        ${paths.encoder}")
                appendLine("  decoder        ${paths.decoder}")
                appendLine("  joiner         ${paths.joiner}")
                appendLine("  tokens         ${paths.tokens}")
                appendLine("  keywords       ${paths.keywords}")
            }
            appendLine("断句             ${vadProblem ?: "可用 ✓"}")
            appendLine("唤醒             ${kwsProblem ?: "可用 ✓"}")
            appendLine("配置加密         ${if (encryptedBacked) "Keystore ✓" else "已降级为普通存储"}")
        }.trim()
    }
}
