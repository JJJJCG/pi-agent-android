package com.pi.assistant.voice

import android.content.Context
import com.pi.assistant.audio.AudioFocusHelper
import com.pi.assistant.audio.ToneCue
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
import kotlinx.coroutines.coroutineScope
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
    private val speaker: TtsSpeaker,
    private val bus: VoiceBus,
) {

    private val focus = AudioFocusHelper(context)

    private val busy = AtomicBoolean(false)

    /** 当前正在跑的流式朗读队列（没有则 null），[stopSpeaking] 靠它做到「点停就停」。 */
    @Volatile
    private var currentQueue: SpeechQueue? = null

    /** 录音期间失去音频焦点 → 停播放，把声道让出去。 */
    init {
        focus.onFocusLost = { stopSpeaking() }
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
            // record() 返回时麦克风已经关了，这时候响不会被自己录进去
            ToneCue.ack()
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

    /**
     * 打断当前朗读。
     *
     * 两条路都要停：流式队列（还要顺带把后续 delta 静音，不然停了又接着念）
     * 和整段播放器。
     */
    fun stopSpeaking() {
        currentQueue?.stop()
        speaker.stop()
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
            // 说完的反馈音：和开头那声 ding() 配成一对（一声到你了 / 两声听完了）
            ToneCue.ack()
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
            // 能流式就边收边念：pi 吐一句，这边合成一句、播一句。
            // 落库放在回调里 —— 播放还没结束就该在聊天列表里看到这条回复。
            val answer = askAndSpeak(text) { ok -> storeReply(ok) }

            when (answer) {
                is PiResult.Ok -> Unit   // 落库与朗读都在 askAndSpeak 里完成了
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
                    // 流式的中途失败未必有 HTTP 码（可能是 SSE 里的 error 帧），
                    // 所以优先信服务端给的那句话
                    bus.setError(
                        answer.msg?.takeIf { it.isNotBlank() }
                            ?: "pi 返回 HTTP ${answer.httpCode}"
                    )
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

    /**
     * 问 pi 并朗读回答：**能流式就边收边念**（pi 吐一句，这边合成一句、播一句）。
     *
     * 与整段模式的差别都收在这一个函数里，上层照旧只处理 [PiResult]：
     *   · [onReply] 在**拿到完整回复时就回调**，早于播放结束 ——
     *     用户还在听的时候，聊天列表里就该有这条回复了
     *   · 一个 delta 都没流过（pi 不支持流式 / 起播失败退回整段）时由这里补念，
     *     上层不用关心「到底念过没有」
     */
    private suspend fun askAndSpeak(
        text: String,
        onReply: suspend (PiResult.Ok) -> Unit,
    ): PiResult = coroutineScope {
        val queue = SpeechQueue(speaker, this)
        currentQueue = queue

        var streamed = false
        val result = try {
            pi.askStream(text, voiceTurn = true) { delta ->
                if (!streamed) {
                    // 第一段文本到手才真的开始出声，这时切状态才不算撒谎
                    streamed = true
                    bus.setStage(VoiceStage.SPEAKING)
                }
                queue.feed(delta)
            }
        } catch (t: Throwable) {
            // 异常退出：把消费者协程一起收掉，否则 coroutineScope 会一直等它
            queue.stop()
            currentQueue = null
            throw t
        }

        var audioError: String? = null
        try {
            if (result is PiResult.Ok) onReply(result)
        } finally {
            currentQueue = null
            // 到这里 pi 已经说完（或失败）。等队列把音频放完再返回 ——
            // 调用方靠这个保证「还在出声时绝不恢复麦克风采集」
            audioError = queue.finish()
        }

        if (result is PiResult.Ok) {
            val err = audioError
            when {
                err != null -> bus.setError(err)
                // 没流过任何 delta → 说明走的是整段模式，这里补念
                !streamed -> speakInternal(result.reply)
            }
        }
        result
    }

    /** 落库 + 顶栏概要。语音进来的回答和手打的一视同仁。 */
    private suspend fun storeReply(reply: PiResult.Ok) {
        dao.insert(
            MessageEntity(
                role = ROLE_PI,
                text = reply.reply,
                tools = reply.tools,
                ms = reply.ms,
            )
        )
        bus.setHeadline(MarkdownStripper.preview(reply.reply, 80))
    }

    private suspend fun speakInternal(text: String) {
        if (MarkdownStripper.strip(text).isBlank()) return
        bus.setStage(VoiceStage.SPEAKING)
        // 流式 / 整段的选路与兜底都在 TtsSpeaker 里，这里只负责把错误摆到界面上
        speaker.speak(text)?.let { bus.setError(it) }
    }

    private companion object {
        const val SILENCE_AFTER_PLAYBACK_MS = 500L
    }
}
