package com.pi.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 流式朗读的播放端：一块 PCM 到手就能出声，不等整段合成完。
 *
 * 和 [TtsPlayer]（MediaPlayer + 落盘文件）的分工：
 *   · MediaPlayer 必须先有一个**完整容器**（wav / mp3）才肯起播 —— 天然要等整段合成完
 *   · 这里是裸 PCM16，AudioTrack 边收边灌，首字延迟 = 服务端出第一块音频的时间
 *
 * 格式写死 24kHz / 单声道 / 16bit —— 这是 MiMo 流式（`format: "pcm16"`）的固定规格，
 * 官方文档里也写明了。不去探测格式，是因为探测本身就要先攒够一段才能判断，等于自废武功。
 *
 * **为什么用非阻塞写而不是阻塞写**：阻塞写要么占住一个线程，要么在被 [stop] 打断时
 * 得靠 pause/flush 把一个卡在内核里的 `write()` 撬出来 —— 两个线程抢同一个 AudioTrack，
 * 时序很难论证，还有「刚 flush 完又写进一块、然后永远卡住」的窗口。
 * 非阻塞写让整个循环只剩「查标志 → 写 → 让出 10ms」一个节奏，[stop] 就只是置个标志，
 * 没有竞态；缓冲区满时返回 0 也正好就是我们要的背压。
 */
@Singleton
class PcmStreamPlayer @Inject constructor() {

    /**
     * 一次流式播放的结局。
     *
     * [bytes] 为 0 是唯一需要调用方特殊对待的信号：一个音都没出来，说明失败发生在
     * 「起播之前」（鉴权 / 参数 / 端点不认 stream），值得退回整段合成再试一次。
     * 已经播出去一半的失败就不该重试了 —— 重试只会把前半句念两遍。
     */
    data class Outcome(
        /** 已写进 AudioTrack 的字节数。0 = 一个音都没出。 */
        val bytes: Long,
        /** 被 [stop] 打断（用户点了停 / 失去音频焦点）。 */
        val stopped: Boolean,
        /** 给用户看的一句话，null = 没出错。 */
        val error: String?,
    ) {
        val ok: Boolean get() = error == null && !stopped
    }

    @Volatile
    private var track: AudioTrack? = null

    /** 打断标志。只有 [play] 会清、只有 [stop] 会置，两头都是单向的。 */
    @Volatile
    private var stopRequested = false

    val isPlaying: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /**
     * 把一整条 PCM 流灌进扬声器，**播完才返回**（调用方靠它决定何时恢复麦克风采集）。
     *
     * [chunks] 是冷流，收集过程就是下载过程 —— 上游出错会以异常的形式从这里穿出来，
     * 这里统一收成 [Outcome.error]，调用方不必认识 OkHttp / 序列化那一堆异常。
     */
    suspend fun play(chunks: Flow<ByteArray>): Outcome = withContext(Dispatchers.Default) {
        stopRequested = false

        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minBytes <= 0) {
            // 极少见（设备不支持这个采样率），但发生了要让用户知道而不是静默不出声
            return@withContext Outcome(0, false, "这台设备不支持 24kHz 流式播放")
        }

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_MASK)
                        .build()
                )
                // 留 4 倍余量：起播跟缓冲区大小无关（有数据就出声），
                // 大一点只是多点抗抖动的余量，不会让第一声更晚。
                .setBufferSizeInBytes(minBytes * BUFFER_FACTOR)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack 建不起来", t)
            return@withContext Outcome(0, false, "播放器不可用：${t.message ?: t::class.java.simpleName}")
        }

        track = audioTrack
        var written = 0L
        var error: String? = null
        try {
            audioTrack.play()

            chunks.collect { pcm ->
                if (stopRequested) throw StopSignal()
                var offset = 0
                while (offset < pcm.size) {
                    val n = audioTrack.write(
                        pcm,
                        offset,
                        pcm.size - offset,
                        AudioTrack.WRITE_NON_BLOCKING,
                    )
                    when {
                        n > 0 -> {
                            offset += n
                            written += n
                        }
                        // 缓冲区满了：让出去等播放头把数据吃掉，这就是背压
                        n == 0 -> delay(WRITE_RETRY_MS)
                        else -> throw PlayException(n)
                    }
                    if (stopRequested) throw StopSignal()
                }
            }

            // 收完了不等于播完了：等播放头把已写入的帧吃完，否则 release 会把尾巴切掉。
            // 等待上限 = 缓冲区内剩余数据（几百毫秒的量级），不是整段音频时长。
            drain(audioTrack, written)
        } catch (e: StopSignal) {
            Log.d(TAG, "播放被打断，已写 ${written}B")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            error = t.message?.takeIf { it.isNotBlank() }
                ?: "播放中断（${t::class.java.simpleName}）"
            Log.w(TAG, "流式播放失败，已写 ${written}B", t)
        } finally {
            releaseTrack()
        }

        Outcome(bytes = written, stopped = stopRequested, error = error)
    }

    /**
     * 立刻打断。可以从任意线程调（音频焦点回调、界面按钮都在主线程）。
     *
     * 只做两件事：置标志 + 把缓冲区里还没放的数据丢掉。正在写的那一块会在下一次
     * 循环判断时看到标志并退出，所以这个调用本身不等任何东西。
     */
    fun stop() {
        stopRequested = true
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
    }

    // ------------------------------------------------------------------ 内部

    /** 等已写入的帧真正播完。播放头到点或超时就返回 —— 绝不把调用方卡在这。 */
    private suspend fun drain(audioTrack: AudioTrack, writtenBytes: Long) {
        val targetFrames = (writtenBytes / BYTES_PER_FRAME)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
        while (!stopRequested &&
            audioTrack.playbackHeadPosition < targetFrames &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            delay(DRAIN_POLL_MS)
        }
    }

    private fun releaseTrack() {
        val t = track
        track = null
        if (t == null) return
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    /** 只用来把 collect 从里面拽出来，不对上层可见。 */
    private class StopSignal : Exception("stopped")

    private class PlayException(val code: Int) : Exception("AudioTrack 报错 $code${audioTrackHint(code)}")

    private companion object {
        const val TAG = "PcmStreamPlayer"

        /** MiMo 流式音频的固定规格：24kHz / 单声道 / PCM16LE。 */
        const val SAMPLE_RATE = 24_000
        const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        const val BYTES_PER_FRAME = 2

        const val BUFFER_FACTOR = 4
        const val WRITE_RETRY_MS = 10L
        const val DRAIN_POLL_MS = 20L
        const val DRAIN_TIMEOUT_MS = 5_000L
    }
}

/** 把 AudioTrack 的负错误码翻成人话，方便直接显示给用户。 */
private fun audioTrackHint(code: Int): String = when (code) {
    AudioTrack.ERROR_BAD_VALUE -> "（参数不被支持）"
    AudioTrack.ERROR_INVALID_OPERATION -> "（对象状态不对）"
    AudioTrack.ERROR_DEAD_OBJECT -> "（音频设备已失效或被抢占）"
    else -> ""
}
