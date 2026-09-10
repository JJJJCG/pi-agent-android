package com.pi.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pi.assistant.data.local.MessageDao
import com.pi.assistant.data.local.MessageEntity
import com.pi.assistant.data.local.MessageEntity.Companion.ROLE_PI
import com.pi.assistant.data.local.MessageEntity.Companion.ROLE_USER
import com.pi.assistant.data.pi.PiRepository
import com.pi.assistant.data.pi.PiResult
import com.pi.assistant.data.pi.PiStatus
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.voice.VoiceBus
import com.pi.assistant.voice.VoiceSession
import com.pi.assistant.voice.VoiceStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 输入区下方那条状态带。 */
sealed interface SendPhase {
    data object Idle : SendPhase

    /** 正在等回复，seconds 是已等待秒数。 */
    data class Waiting(val seconds: Long) : SendPhase

    /** 504 且 pi 还忙。**这一态故意不给重发入口**。 */
    data class StillRunning(val hint: String) : SendPhase

    /** 普通提示/报错，可关闭。 */
    data class Notice(val message: String, val isError: Boolean) : SendPhase
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: PiRepository,
    private val dao: MessageDao,
    private val settings: SettingsStore,
    private val voiceSession: VoiceSession,
    private val bus: VoiceBus,
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _phase = MutableStateFlow<SendPhase>(SendPhase.Idle)
    val phase: StateFlow<SendPhase> = _phase.asStateFlow()

    private val _status = MutableStateFlow<PiStatus?>(null)
    val status: StateFlow<PiStatus?> = _status.asStateFlow()

    private var tickerJob: Job? = null

    val isSending: Boolean get() = _phase.value is SendPhase.Waiting

    // ---- 语音（M2）：界面只读这些，具体动作全在 VoiceSession 里

    val voiceStage: StateFlow<VoiceStage> = bus.stage
    val voiceError: StateFlow<String?> = bus.error

    /** 非 null 表示这台设备跑不了端侧 VAD（缺 so 或模型），界面据此禁用麦克风。 */
    val micUnavailableReason: String? get() = voiceSession.micUnavailableReason

    init {
        refreshStatus()
    }

    fun onInputChange(value: String) {
        if (!isSending) _input.value = value
    }

    fun refreshStatus() {
        viewModelScope.launch { _status.value = repository.statusOrNull() }
    }

    fun dismissNotice() {
        val current = _phase.value
        if (current is SendPhase.Notice || current is SendPhase.StillRunning) {
            _phase.value = SendPhase.Idle
        }
    }

    fun clearHistory() {
        viewModelScope.launch { dao.clear() }
    }

    /** 发送输入框里的内容：先落一条用户气泡，再问 pi。 */
    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty() || isSending) return
        _input.value = ""
        dispatch(text, insertUserBubble = true)
    }

    /** 重发某条失败的消息：砍掉它之后的残局，原地再来一次，避免历史错乱。 */
    fun resend(message: MessageEntity) {
        if (isSending) return
        viewModelScope.launch {
            dao.setFailed(message.id, false)
            dao.deleteAfter(message.id)
        }
        dispatch(message.text, insertUserBubble = false)
    }

    /**
     * 按麦克风说话：录音 → VAD 自动断句 → ASR。
     * 结果**只回填到输入框**，不直接发出去 —— 识别错字能改，
     * 这点体验差距比省一次点击重要得多。
     */
    fun startVoiceInput() {
        if (isSending) return
        viewModelScope.launch {
            val text = voiceSession.captureToText()
            if (!text.isNullOrBlank()) {
                _input.value = text
            }
        }
    }

    /** 朗读一条回复。 */
    fun speak(text: String) {
        viewModelScope.launch { voiceSession.speak(text) }
    }

    fun stopSpeaking() {
        voiceSession.stopSpeaking()
    }

    fun dismissVoiceError() {
        bus.clearError()
    }

    private fun dispatch(text: String, insertUserBubble: Boolean) {
        viewModelScope.launch {
            val userId = if (insertUserBubble) {
                dao.insert(MessageEntity(role = ROLE_USER, text = text))
            } else {
                -1L
            }

            // 发送前已知 pi 忙，先给个心理预期（不阻塞，pi 那边本来就会排队）
            if (_status.value?.busy == true) {
                _phase.value = SendPhase.Notice("pi 当前正忙，这条会排在前一轮之后", isError = false)
            }

            startTicker()
            val result = repository.ask(text)
            stopTicker()
            apply(result, userId)
        }
    }

    private suspend fun apply(result: PiResult, userId: Long) {
        when (result) {
            is PiResult.Ok -> {
                dao.insert(
                    MessageEntity(
                        role = ROLE_PI,
                        text = result.reply,
                        tools = result.tools,
                        ms = result.ms,
                    )
                )
                _phase.value = SendPhase.Idle
                refreshStatus()
                if (settings.current.speech.autoSpeak) {
                    viewModelScope.launch { voiceSession.speak(result.reply) }
                }
            }

            is PiResult.StillRunning -> {
                // 关键：不标失败、不给重发按钮。任务还在 pi 那边跑。
                _phase.value = SendPhase.StillRunning(result.hint)
                refreshStatus()
            }

            PiResult.AuthError -> {
                dao.setFailed(userId, true)
                _phase.value = SendPhase.Notice(
                    "token 无效或缺失：去设置页把 /root/.pi/agent/http-bridge.json 里的 token 填上",
                    isError = true,
                )
            }

            is PiResult.Unavailable -> {
                dao.setFailed(userId, true)
                _phase.value = SendPhase.Notice(
                    "连不上 pi：确认 pi 已重启、手机与它同一局域网；可到设置页点「探活」",
                    isError = true,
                )
            }

            is PiResult.NotConfigured -> {
                dao.setFailed(userId, true)
                _phase.value = SendPhase.Notice(
                    "还没配置 pi 地址（${result.what}），先到设置页填一下",
                    isError = true,
                )
            }

            is PiResult.Failed -> {
                dao.setFailed(userId, true)
                val detail = listOfNotNull(
                    result.code,
                    result.msg?.take(120),
                ).joinToString(" · ")
                _phase.value = SendPhase.Notice(
                    "请求失败 HTTP ${result.httpCode}" + if (detail.isEmpty()) "" else "：$detail",
                    isError = true,
                )
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var seconds = 0L
            while (isActive) {
                _phase.value = SendPhase.Waiting(seconds)
                delay(1_000)
                seconds += 1
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    override fun onCleared() {
        stopTicker()
        super.onCleared()
    }
}
