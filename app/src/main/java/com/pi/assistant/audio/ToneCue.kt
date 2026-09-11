package com.pi.assistant.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlin.concurrent.thread

/**
 * 对话流程的两声提示音。
 *
 * 它们不是装饰：唤醒词和「说完自动停」都发生在用户看不到的时序里，
 * 没有声音反馈用户只能靠猜 —— 于是重复喊唤醒词、或者说完还一直等着。
 */
object ToneCue {

    /**
     * 开口前的一声「叮」。
     *
     * 用户说完唤醒词需要一个「我听到了」的反馈，
     * 否则他会重复喊，反而把误唤醒概率拉高。
     */
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

    /**
     * 说完之后的两声「叮·叮」—— 麦克风关闭、准备送去识别时响。
     *
     * 和 [ding] 配成一对：**一声 = 到你了，两声 = 我听完了**。
     * 短促的两声听起来是「收到」，比换一个音色更好认 —— 用户不需要知道
     * 我们换了哪一股频率，只需要知道「一声还是两声」。
     *
     * 调用时机有讲究：**必须在麦克风关掉之后**。提前几毫秒响就会把这声
     * 录进自己的录音里，那还不如不给。
     * 两声之间留 170ms 而不是贴在一起，贴太近会被听成一声粗糙的长音。
     */
    fun ack() {
        thread(name = "tone-cue") {
            val generator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }
                .getOrNull() ?: return@thread
            try {
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                Thread.sleep(170)
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                // 等多个 230ms 再 release，否则第二声会被掐掉尾巴
                Thread.sleep(230)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { generator.release() }
            }
        }
    }
}
