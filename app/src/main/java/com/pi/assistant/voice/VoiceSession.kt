package com.pi.assistant.voice

import android.content.Context
import com.pi.assistant.audio.AudioFocusHelper
import com.pi.assistant.audio.ToneCue
import com.pi.assistant.audio.TtsPlayer
import com.pi.assistant.audio.VadRecorder
import com.pi.assistant.data.local.MessageDao
import com.pi.assistant.data.local.MessageEntity
import com.pi.assistant.data.local.MessageEntity.Companion.ROLE_PI
import com.pi.assistant.data.local.MessageEntity.Companion.ROLE_USER
import com.pi.assistant.data.pi.PiRepository
import com.pi.assistant.data.pi.PiResult
import com.pi.assistant.data.speech.SpeechRepository
import com.pi.assistant.data.speech.SpeechResult
import com.pi.assistant.util.MarkdownStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音链路编排器 —— 三个阶段（VAD / ASR / TTS）在这里接起来。
 *
 * 对外只有三个入口：
 *   · [captureToText] 手动按麦克风：录音 + 识别，文字回填输入框让你改
 *   · [speak]         朗读一段文字
 *   · [runWakeTurn]   唤醒后的完整一轮：提示音 → 录音 → 识别 → 问 pi → 朗读
 *
 * 用 [busy] 串行化：前台服务在跑一轮时，界面上的按钮自动无效，
 * 避免两条链路同时抢麦克风和音频焦点。
 */
@Singleton
class VoiceSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vadRecorder: VadRecorder,
    private val speech: SpeechRepository,
    private val pi: PiRepository,
    private val dao: MessageDao,
    private val ttsPlayer: TtsPlayer,
    private val bus: VoiceBus,
) {

    private val focus = AudioFocusHelper(context)

    private val busy = AtomicBoolean(false)

    /** 录音期间失去音频焦点 → 停播放，把声道让出去。 */
    init {
        focus.onFocusLost = { ttsPlayer.stop() }
    }

    val isBusy: Boolean get() = busy.get()

    /** 非 null = 端侧 VAD 不可用（缺 so 或模型），界面据此禁用麦克风。 */
    val micUnavailableReason: String? get() = vadRecorder.unavailableReason()

    // ------------------------------------------------------------ 手动录音

    /** 返回识别文本；失败返回 null 并把原因写进 [VoiceBus.error]。 */
    suspend fun captureToText(): String? {
        vadRecorder.unavailableReason()?.let {
            bus.setError(it)
            return null
        }
        if (!busy.compareAndSet(false, true)) return null

        var wav: File? = null
        try {
            bus.clearError()
            bus.setStage(VoiceStage.RECORDING)
            wav = vadRecorder.record() ?: run {
                bus.setError("没听到有效语音，再说一次？")
                return null
            }

            bus.setStage(VoiceStage.TRANSCRIBING)
            return when (val result = speech.transcribe(wav)) {
                is SpeechResult.Ok -> {
                    bus.setHeadline(result.value)
                    result.value
                }
                is SpeechResult.Failed -> {
                    bus.setError(result.message)
                    null
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            bus.setError("录音识别出错：${t.message ?: t::class.java.simpleName}")
            return null
        } finally {
            wav?.delete()
            bus.setStage(VoiceStage.IDLE)
            busy.set(false)
        }
    }

    // -------------------------------------------------------------- 朗读

    /** 播完才返回。被 [stopSpeaking] 打断则提前返回。 */
    suspend fun speak(text: String) {
        if (!busy.compareAndSet(false, true)) return
        try {
            focus.request()
            speakInternal(text)
        } finally {
            focus.release()
            bus.setStage(VoiceStage.IDLE)
            busy.set(false)
        }
    }

    fun stopSpeaking() {
        ttsPlayer.stop()
    }

    // --------------------------------------------------------- 唤醒后一轮

    /**
     * 调用方（前台服务）负责在调用前停掉 KWS、调用后恢复，
     * 这里只管「一轮对话」本身。
     *
     * 结束后会多静默 [SILENCE_AFTER_PLAYBACK_MS] 再返回 —— 播放刚停时
     * 扬声器的余音还在，立刻开麦很容易被自己的尾音触发。
     */
    suspend fun runWakeTurn() {
        if (!busy.compareAndSet(false, true)) return

        var wav: File? = null
        try {
            bus.clearError()

            ToneCue.ding()
            focus.request()

            bus.setStage(VoiceStage.RECORDING)
            wav = vadRecorder.record() ?: run {
                bus.setError("没听到你说什么")
                return
            }

            bus.setStage(VoiceStage.TRANSCRIBING)
            val text = when (val result = speech.transcribe(wav)) {
                is SpeechResult.Ok -> result.value
                is SpeechResult.Failed -> {
                    bus.setError(result.message)
                    return
                }
            }
            if (text.isBlank()) {
                bus.setError("识别结果是空的")
                return
            }

            bus.setHeadline(text)
            wav.delete()
            wav = null

            // 语音进来的消息也进本地历史，跟手打的一视同仁
            val userRow = dao.insert(MessageEntity(role = ROLE_USER, text = text))

            bus.setStage(VoiceStage.ASKING)
            when (val answer = pi.ask(text)) {
                is PiResult.Ok -> {
                    dao.insert(
                        MessageEntity(
                            role = ROLE_PI,
                            text = answer.reply,
                            tools = answer.tools,
                            ms = answer.ms,
                        )
                    )
                    bus.setHeadline(MarkdownStripper.preview(answer.reply, 80))
                    speakInternal(answer.reply)
                }

                is PiResult.StillRunning -> bus.setError(answer.hint)

                PiResult.AuthError -> {
                    dao.setFailed(userRow, true)
                    bus.setError("token 无效或缺失，去设置页检查")
                }

                is PiResult.Unavailable -> {
                    dao.setFailed(userRow, true)
                    bus.setError("连不上 pi，确认它已启动且和手机同网段")
                }

                is PiResult.NotConfigured -> {
                    dao.setFailed(userRow, true)
                    bus.setError("还没配置 pi 地址")
                }

                is PiResult.Failed -> {
                    dao.setFailed(userRow, true)
                    bus.setError("pi 返回 HTTP ${answer.httpCode}")
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            bus.setError("这一轮失败了：${t.message ?: t::class.java.simpleName}")
        } finally {
            wav?.delete()
            focus.release()
            delay(SILENCE_AFTER_PLAYBACK_MS)
            bus.setStage(VoiceStage.IDLE)
            busy.set(false)
        }
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun speakInternal(text: String) {
        if (MarkdownStripper.strip(text).isBlank()) return
        bus.setStage(VoiceStage.SPEAKING)

        when (val audio = speech.synthesize(text)) {
            is SpeechResult.Ok -> {
                val file = audio.value
                try {
                    ttsPlayer.play(file)
                } finally {
                    file.delete()
                }
            }
            is SpeechResult.Failed -> bus.setError(audio.message)
        }
    }

    private companion object {
        const val SILENCE_AFTER_PLAYBACK_MS = 500L
    }
}
