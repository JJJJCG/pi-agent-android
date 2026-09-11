package com.pi.assistant.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pi.assistant.audio.KwsEngine
import com.pi.assistant.audio.TtsPlayer
import com.pi.assistant.audio.VadRecorder
import com.pi.assistant.data.local.MessageDao
import com.pi.assistant.data.prefs.MimoSpeech
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.data.prefs.ThemeMode
import com.pi.assistant.data.pi.PiRepository
import com.pi.assistant.data.pi.ProbeResult
import com.pi.assistant.data.speech.SpeechRepository
import com.pi.assistant.data.speech.SpeechResult
import com.pi.assistant.service.WakeWordService
import com.pi.assistant.voice.VoiceBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 唤醒开关的三种真实状态。 */
enum class WakeState {
    /** 配置就是关的。 */
    OFF,

    /** 配置开着，服务也确实在跑。 */
    RUNNING,

    /** 配置开着，但服务没在跑 —— 多半被系统杀了，需要用户手动拉起来。 */
    NOT_RUNNING,
}

/**
 * 设置页的编辑态。
 *
 * 数字类型的字段一律用 String 存：输入框删空时如果落成 0，用户会莫名其妙
 * 得到一个「超时 0 秒」的配置，不如先留着空串，保存时再统一兜底。
 */
data class SettingsDraft(
    // pi 连接
    val baseUrl: String = PiSettings.DEFAULT_BASE_URL,
    val token: String = "",
    val timeoutSec: String = PiSettings.DEFAULT_TIMEOUT_SEC.toString(),

    // 语音：识别和朗读都用小米 MiMo，共用一份地址和 key
    val mimoBaseUrl: String = MimoSpeech.DEFAULT_BASE_URL,
    val mimoToken: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
    val asrLanguage: String = "auto",
    val ttsModel: String = DEFAULT_TTS_MODEL,
    val ttsVoice: String = "mimo_default",
    val ttsStylePrompt: String = "",
    val ttsSpeed: String = "1.0",
    val ttsFormat: String = "wav",
    val autoSpeak: Boolean = false,

    // VAD
    val vadThreshold: String = "0.5",
    val vadMinSilenceMs: String = "600",
    val maxRecordSeconds: String = "15",

    // 唤醒
    val wakeKeyword: String = "小派同学",
    val kwsScore: String = "1.5",
    val kwsThreshold: String = "0.25",
    val kwsThreads: String = "1",
    val wakeOnlyCharging: Boolean = false,
    val wakeOnlyWifi: Boolean = false,
    val wakeWifiSsid: String = "",
    val wakeStartHour: String = PiSettings.HOUR_ANY.toString(),
    val wakeEndHour: String = PiSettings.HOUR_ANY.toString(),

    // 隐私
    val hideRecents: Boolean = false,

    // 仅界面态
    val tokenVisible: Boolean = false,
    val probing: Boolean = false,
    val probeText: String? = null,
    val probeIsError: Boolean = false,
    val testingSpeech: Boolean = false,
    val testingAsr: Boolean = false,
) {
    /** 地址和 key 都齐了才发得出去请求；模型名空着服务端也不认。 */
    private val speechReady: Boolean
        get() = mimoBaseUrl.isNotBlank() && mimoToken.isNotBlank()

    val asrConfigured: Boolean get() = speechReady && asrModel.isNotBlank()

    val ttsConfigured: Boolean get() = speechReady && ttsModel.isNotBlank()

    companion object {
        const val DEFAULT_ASR_MODEL = "mimo-v2.5-asr"
        const val DEFAULT_TTS_MODEL = "mimo-v2.5-tts"

        /** MiMo 支持的三档语种。明确语种能提升识别效果，所以给成可点的选项。 */
        val ASR_LANGUAGES = listOf("auto", "zh", "en")
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val repository: PiRepository,
    private val dao: MessageDao,
    private val speech: SpeechRepository,
    private val ttsPlayer: TtsPlayer,
    private val vadRecorder: VadRecorder,
    private val kwsEngine: KwsEngine,
    private val bus: VoiceBus,
) : ViewModel() {

    private val _draft = MutableStateFlow(settings.current.toDraft())
    val draft: StateFlow<SettingsDraft> = _draft.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = settings.state
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.themeMode)

    val wakeEnabled: StateFlow<Boolean> = settings.state
        .map { it.wakeEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.wakeEnabled)

    /**
     * 配置说的和实际在跑的，可能不是一回事。
     *
     * 典型场景：昨天开了唤醒，系统今天把进程杀了 —— 配置里还是 `true`，
     * 但没人在听。界面必须说实话，否则用户以为开着，喊半天没反应。
     */
    val wakeState: StateFlow<WakeState> = combine(settings.state, bus.serviceRunning) { snapshot, running ->
        when {
            !snapshot.wakeEnabled -> WakeState.OFF
            running -> WakeState.RUNNING
            else -> WakeState.NOT_RUNNING
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WakeState.OFF)

    val encryptedBacked: Boolean get() = settings.encryptedBacked

    /** 端侧 VAD 不可用的原因（缺 so / 缺模型），null 表示可用。 */
    val micProblem: String? get() = vadRecorder.unavailableReason()

    /** 唤醒引擎不可用的原因，null 表示可用。 */
    val wakeProblem: String? get() = kwsEngine.unavailableReason()

    val batteryOptimized: Boolean
        get() = runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName)?.not() ?: false
        }.getOrDefault(false)

    /**
     * 读 Wi-Fi 名要的运行时权限，双轨（见 Manifest 注释）：
     * 13+ 是「附近设备」（neverForLocation，不碰定位），12 及以下是定位。
     */
    val wifiNamePermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    val hasWifiNamePermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, wifiNamePermission) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ 字段更新

    fun updateBaseUrl(value: String) = mutate { it.copy(baseUrl = value) }
    fun updateToken(value: String) = mutate { it.copy(token = value) }
    fun updateTimeout(value: String) = mutate { it.copy(timeoutSec = value.digits(4)) }
    fun toggleTokenVisible() = mutate { it.copy(tokenVisible = !it.tokenVisible) }

    // ---- 语音：识别和朗读共用一份地址与 key
    fun updateMimoBaseUrl(value: String) = mutate { it.copy(mimoBaseUrl = value) }
    fun updateMimoToken(value: String) = mutate { it.copy(mimoToken = value) }

    // ---- 识别
    fun updateAsrModel(value: String) = mutate { it.copy(asrModel = value) }
    fun updateAsrLanguage(value: String) = mutate { it.copy(asrLanguage = value) }

    // ---- 朗读
    fun updateTtsModel(value: String) = mutate { it.copy(ttsModel = value) }
    fun updateTtsVoice(value: String) = mutate { it.copy(ttsVoice = value) }
    fun updateTtsStylePrompt(value: String) = mutate { it.copy(ttsStylePrompt = value) }
    fun updateTtsFormat(value: String) = mutate { it.copy(ttsFormat = value) }
    fun updateTtsSpeed(value: String) = mutate { it.copy(ttsSpeed = value) }
    fun toggleAutoSpeak() = mutate { it.copy(autoSpeak = !it.autoSpeak) }

    fun updateVadThreshold(value: String) = mutate { it.copy(vadThreshold = value) }
    fun updateVadSilence(value: String) = mutate { it.copy(vadMinSilenceMs = value.digits(4)) }
    fun updateMaxRecord(value: String) = mutate { it.copy(maxRecordSeconds = value.digits(3)) }

    fun updateWakeKeyword(value: String) = mutate { it.copy(wakeKeyword = value) }
    fun updateKwsScore(value: String) = mutate { it.copy(kwsScore = value) }
    fun updateKwsThreshold(value: String) = mutate { it.copy(kwsThreshold = value) }
    fun updateKwsThreads(value: String) = mutate { it.copy(kwsThreads = value.digits(1)) }
    fun toggleWakeCharging() = mutate { it.copy(wakeOnlyCharging = !it.wakeOnlyCharging) }
    fun toggleWakeWifi() = mutate { it.copy(wakeOnlyWifi = !it.wakeOnlyWifi) }
    fun updateWakeWifiSsid(value: String) = mutate { it.copy(wakeWifiSsid = value) }
    fun toggleHideRecents() = mutate { it.copy(hideRecents = !it.hideRecents) }
    fun updateWakeStart(value: String) = mutate { it.copy(wakeStartHour = value) }
    fun updateWakeEnd(value: String) = mutate { it.copy(wakeEndHour = value) }

    // -------------------------------------------------------------- 动作

    fun consumeToast() {
        _toast.value = null
    }

    /** 把草稿整份落盘。所有保存入口都走它，避免漏字段把配置抹掉。 */
    fun saveAll(showToast: Boolean = true) {
        val draft = _draft.value
        val baseUrl = SettingsStore.normalizeBaseUrl(draft.baseUrl)
        if (baseUrl.isBlank()) {
            _toast.value = "pi 地址不能为空"
            return
        }

        settings.save(
            PiSettings(
                baseUrl = baseUrl,
                token = draft.token.trim(),
                timeoutSec = draft.timeoutSec.toIntOrNull()
                    ?.coerceIn(PiSettings.TIMEOUT_MIN, PiSettings.TIMEOUT_MAX)
                    ?: PiSettings.DEFAULT_TIMEOUT_SEC,
                themeMode = settings.current.themeMode,

                speech = MimoSpeech(
                    // 地址的规范化交给 SettingsStore.save() 统一做，这里不重复
                    baseUrl = draft.mimoBaseUrl,
                    token = draft.mimoToken.trim(),
                    asrModel = draft.asrModel.trim().ifBlank { SettingsDraft.DEFAULT_ASR_MODEL },
                    asrLanguage = draft.asrLanguage.trim().ifBlank { "auto" },
                    ttsModel = draft.ttsModel.trim().ifBlank { SettingsDraft.DEFAULT_TTS_MODEL },
                    ttsVoice = draft.ttsVoice.trim(),
                    ttsStylePrompt = draft.ttsStylePrompt.trim(),
                    ttsSpeed = draft.ttsSpeed.toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: 1.0f,
                    ttsFormat = draft.ttsFormat.trim().ifBlank { "wav" },
                    autoSpeak = draft.autoSpeak,
                ),

                vadThreshold = draft.vadThreshold.toFloatOrNull()?.coerceIn(0.05f, 0.95f) ?: 0.5f,
                vadMinSilenceMs = draft.vadMinSilenceMs.toIntOrNull()?.coerceIn(200, 3000) ?: 600,
                maxRecordSeconds = draft.maxRecordSeconds.toIntOrNull()?.coerceIn(3, 60) ?: 15,

                wakeEnabled = settings.current.wakeEnabled,
                wakeKeyword = draft.wakeKeyword.trim().ifBlank { "小派同学" },
                kwsScore = draft.kwsScore.toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1.5f,
                kwsThreshold = draft.kwsThreshold.toFloatOrNull()?.coerceIn(0.01f, 0.9f) ?: 0.25f,
                kwsThreads = draft.kwsThreads.toIntOrNull()?.coerceIn(1, 4) ?: 1,
                wakeOnlyCharging = draft.wakeOnlyCharging,
                wakeOnlyWifi = draft.wakeOnlyWifi,
                // 容错：用户从 Wi-Fi 详情页复制过来可能自带引号
                wakeWifiSsid = draft.wakeWifiSsid.trim().removeSurrounding("\"").trim(),
                wakeStartHour = draft.wakeStartHour.toIntOrNull() ?: PiSettings.HOUR_ANY,
                wakeEndHour = draft.wakeEndHour.toIntOrNull() ?: PiSettings.HOUR_ANY,
                hideRecents = draft.hideRecents,
            )
        )

        // 回填规范化后的结果，让用户看见「http:// 被自动补上了」
        _draft.value = settings.current.toDraft().copy(
            tokenVisible = draft.tokenVisible,
            probeText = draft.probeText,
            probeIsError = draft.probeIsError,
        )
        if (showToast) {
            val saved = settings.current
            _toast.value = if (saved.speech.autoSpeak && !saved.speech.ttsConfigured) {
                "已保存，但语音还没配好（缺 API Key），自动朗读不会生效"
            } else {
                "已保存"
            }
        }
    }

    fun updateTheme(mode: ThemeMode) = settings.updateThemeMode(mode)

    /** 探活：先落盘（否则探的还是旧地址），再打 /healthz。 */
    fun probe() {
        saveAll(showToast = false)
        mutate { it.copy(probing = true, probeText = null) }
        viewModelScope.launch {
            val result = repository.probe()
            mutate {
                when (result) {
                    is ProbeResult.Ok -> it.copy(
                        probing = false,
                        probeText = "连通 ✓ 响应「${result.text}」，${result.ms}ms",
                        probeIsError = false,
                    )
                    is ProbeResult.Fail -> it.copy(
                        probing = false,
                        probeText = "不通 ✗ ${result.message}",
                        probeIsError = true,
                    )
                }
            }
        }
    }

    /** 想验证语音端点，合成一句真话听听比看文档靠谱。 */
    fun testSpeak() {
        saveAll(showToast = false)
        mutate { it.copy(testingSpeech = true) }
        viewModelScope.launch {
            when (val result = speech.synthesize(TEST_SENTENCE)) {
                is SpeechResult.Ok -> {
                    val file = result.value
                    try {
                        ttsPlayer.play(file)
                    } finally {
                        file.delete()
                    }
                }
                is SpeechResult.Failed -> _toast.value = result.message
            }
            mutate { it.copy(testingSpeech = false) }
        }
    }

    /**
     * 录一句真话打给识别接口。
     *
     * 「试听」只能验合成那一半，识别侧没有验证手段 —— 对着一套刚填好的 key，
     * 能不能出字、语种给对没有，只能真录一句才知道。
     */
    fun testTranscribe() {
        vadRecorder.unavailableReason()?.let {
            _toast.value = "端侧断句不可用：$it"
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _toast.value = "请先授予录音权限"
            return
        }
        saveAll(showToast = false)
        mutate { it.copy(testingAsr = true) }
        viewModelScope.launch {
            _toast.value = "请说一句话，说完自动停…"
            val wav = vadRecorder.record()
            if (wav == null) {
                _toast.value = "没听到有效人声，再试一次"
                mutate { it.copy(testingAsr = false) }
                return@launch
            }
            try {
                when (val result = speech.transcribe(wav)) {
                    is SpeechResult.Ok -> _toast.value = "识别到：「${result.value}」"
                    is SpeechResult.Failed -> _toast.value = result.message
                }
            } finally {
                wav.delete()
                mutate { it.copy(testingAsr = false) }
            }
        }
    }

    /** 开关唤醒服务。这是唯一合规的启动来源 —— 不做自启动。 */
    fun setWakeEnabled(enabled: Boolean) {
        if (enabled) {
            kwsEngine.unavailableReason()?.let {
                _toast.value = "唤醒不可用：$it"
                return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                _toast.value = "请先授予录音权限"
                return
            }
            saveAll(showToast = false)
            settings.updateWakeEnabled(true)
            WakeWordService.start(context)
            val wakeSaved = settings.current
            _toast.value =
                if (wakeSaved.wakeOnlyWifi && wakeSaved.wakeWifiSsid.isNotBlank() && !hasWifiNamePermission) {
                    "已开启唤醒，但读不到 Wi-Fi 名（缺权限），指定 Wi-Fi 条件不会满足"
                } else {
                    "已开启唤醒，麦克风将常驻采集"
                }
        } else {
            settings.updateWakeEnabled(false)
            WakeWordService.stop(context)
            _toast.value = "已关闭唤醒"
        }
    }

    /** 跳系统的电池优化白名单页。厂商保活只能尽力而为，代码侧能做的就到这。 */
    fun requestIgnoreBatteryOptimizations(): Boolean {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            // 有些 ROM 砍掉了这个 action，退到应用详情页
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clear()
            _toast.value = "本地历史已清空"
        }
    }

    // -------------------------------------------------------------- 内部

    private fun mutate(block: (SettingsDraft) -> SettingsDraft) {
        _draft.value = block(_draft.value)
    }

    private fun String.digits(max: Int): String = filter { it.isDigit() }.take(max)

    private companion object {
        const val TEST_SENTENCE = "你好，我是派助手，语音通道正常。"
    }
}

/** 从已保存配置反向生成编辑态。 */
private fun PiSettings.toDraft(): SettingsDraft = SettingsDraft(
    baseUrl = baseUrl,
    token = token,
    timeoutSec = timeoutSec.toString(),
    mimoBaseUrl = speech.baseUrl,
    mimoToken = speech.token,
    asrModel = speech.asrModel,
    asrLanguage = speech.asrLanguage,
    ttsModel = speech.ttsModel,
    ttsVoice = speech.ttsVoice,
    ttsStylePrompt = speech.ttsStylePrompt,
    ttsSpeed = speech.ttsSpeed.toString(),
    ttsFormat = speech.ttsFormat,
    autoSpeak = speech.autoSpeak,
    vadThreshold = vadThreshold.toString(),
    vadMinSilenceMs = vadMinSilenceMs.toString(),
    maxRecordSeconds = maxRecordSeconds.toString(),
    wakeKeyword = wakeKeyword,
    kwsScore = kwsScore.toString(),
    kwsThreshold = kwsThreshold.toString(),
    kwsThreads = kwsThreads.toString(),
    wakeOnlyCharging = wakeOnlyCharging,
    wakeOnlyWifi = wakeOnlyWifi,
    wakeWifiSsid = wakeWifiSsid,
    wakeStartHour = wakeStartHour.toString(),
    wakeEndHour = wakeEndHour.toString(),
    hideRecents = hideRecents,
)
