package com.pi.assistant.voice

import android.util.Log
import com.pi.assistant.audio.PcmStreamPlayer
import com.pi.assistant.audio.TtsPlayer
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.data.speech.SpeechRepository
import com.pi.assistant.data.speech.SpeechResult
import com.pi.assistant.util.MarkdownStripper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「把一段文本念出来」的唯一入口。
 *
 * 两条路：
 *   · **流式**（默认）：边合成边播，首字延迟 = 服务端吐出第一块音频的时间。
 *     配合 [PcmStreamPlayer]，整段朗读期间不用落盘一个字节。
 *   · **整段**：合成完落盘再播。平时不用，但它是两条命脉 ——
 *     (1) 用户关掉流式开关时（中转端点不认 `stream` 参数）；
 *     (2) 流式「一个音都没出来」时的自动兜底。
 *
 * 选路和兜底只在这里维护一份：[VoiceSession] 和设置页的「试听」都走它，
 * 免得两处的行为悄悄分叉。
 */
@Singleton
class TtsSpeaker @Inject constructor(
    private val settings: SettingsStore,
    private val speech: SpeechRepository,
    private val streamPlayer: PcmStreamPlayer,
    private val filePlayer: TtsPlayer,
) {

    /**
     * 念一段文本，**播完才返回**。
     *
     * 返回给用户看的错误文案，null = 正常。被打断（用户点停 / 失去音频焦点）不算错，
     * 返回 null —— 那是用户的意图，不该弹提示。
     */
    suspend fun speak(text: String): String? {
        if (MarkdownStripper.strip(text).isBlank()) return null

        if (!settings.current.speech.ttsStream) return speakBuffered(text)

        val outcome = streamPlayer.play(speech.synthesizeStream(text))
        return when {
            outcome.ok -> null

            // 一个音都没出来 → 失败发生在起播之前（鉴权 / 参数 / 端点不认 stream）。
            // 退回整段合成再试一次：对「端点不支持流式」这种情况是唯一能救回来的办法。
            outcome.bytes == 0L && !outcome.stopped -> {
                Log.w(TAG, "流式一个音都没出（${outcome.error}），退回整段合成")
                speakBuffered(text)
            }

            // 已经播了一半被打断：不报错、也不重播 —— 重播会把前半句念两遍
            else -> if (outcome.stopped) null else outcome.error
        }
    }

    /** 打断当前播放。可以从任意线程调。 */
    fun stop() {
        streamPlayer.stop()
        filePlayer.stop()
    }

    // ------------------------------------------------------------------ 内部

    /** 整段合成 + 落盘 + MediaPlayer。慢，但兼容性最好。 */
    private suspend fun speakBuffered(text: String): String? =
        when (val audio = speech.synthesize(text)) {
            is SpeechResult.Ok -> {
                val file = audio.value
                try {
                    filePlayer.play(file)
                    null
                } finally {
                    // 播完就删：这些东西只是过路，留着只会占 cacheDir（A8）
                    file.delete()
                }
            }

            is SpeechResult.Failed -> audio.message
        }

    private companion object {
        const val TAG = "TtsSpeaker"
    }
}
