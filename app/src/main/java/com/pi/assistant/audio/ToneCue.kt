package com.pi.assistant.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlin.concurrent.thread

/**
 * 唤醒后的「叮」。
 *
 * 这声提示音不是装饰：用户说完唤醒词需要一个「我听到了」的反馈，
 * 否则他会重复喊，反而把误唤醒概率拉高。
 */
object ToneCue {

    fun ding() {
        thread(name = "tone-cue") {
            val generator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }
                .getOrNull() ?: return@thread
            try {
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
                Thread.sleep(260)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { generator.release() }
            }
        }
    }
}
