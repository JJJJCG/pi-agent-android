package com.pi.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 语音链路当前走到哪一步。 */
enum class VoiceStage {
    IDLE,
    WAITING_WAKE,
    RECORDING,
    TRANSCRIBING,
    ASKING,
    SPEAKING,
    ERROR;

    val label: String
        get() = when (this) {
            IDLE -> "待命"
            WAITING_WAKE -> "在听唤醒词"
            RECORDING -> "正在听你说"
            TRANSCRIBING -> "识别中"
            ASKING -> "问 pi"
            SPEAKING -> "朗读回复"
            ERROR -> "出错了"
        }
}

/**
 * 语音状态总线。
 *
 * 前台服务和界面是两个不同生命周期的东西，靠这个单例把它们接起来：
 * 服务写、界面读，谁也不用持有谁。
 */
@Singleton
class VoiceBus @Inject constructor() {

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _stage = MutableStateFlow(VoiceStage.IDLE)
    val stage: StateFlow<VoiceStage> = _stage.asStateFlow()

    /** 最近一次识别到的原话，或最近一条状态文案。 */
    private val _headline = MutableStateFlow<String?>(null)
    val headline: StateFlow<String?> = _headline.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _wakeCount = MutableStateFlow(0)
    val wakeCount: StateFlow<Int> = _wakeCount.asStateFlow()

    /** 最近一次唤醒词与它的时间戳，调试页用来标定阈值。 */
    private val _lastWake = MutableStateFlow<String?>(null)
    val lastWake: StateFlow<String?> = _lastWake.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) _stage.value = VoiceStage.IDLE
    }

    fun setStage(stage: VoiceStage) {
        _stage.value = stage
        if (stage != VoiceStage.ERROR) _error.value = null
    }

    fun setHeadline(text: String?) {
        _headline.value = text
    }

    fun setError(message: String?) {
        _error.value = message
        if (message != null) _stage.value = VoiceStage.ERROR
    }

    fun clearError() {
        _error.value = null
        if (_stage.value == VoiceStage.ERROR) _stage.value = VoiceStage.IDLE
    }

    fun onWake(keyword: String) {
        _wakeCount.value += 1
        _lastWake.value = keyword
    }
}
