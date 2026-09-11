package com.pi.assistant.voice

import com.pi.assistant.audio.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 「边收边念」的播放队列：pi 吐一句，这里就念一句。
 *
 * 为什么需要它 —— 流式回复的 `delta` 是一段段文本，而朗读必须**严格串行**：
 *   · 不能等全文到齐再念，那就退化成整段模式了
 *   · 也不能来一段就并发合成一段，那样会串台词（两句同时开口）
 * 中间放一个单消费者的队列，上游有多少收多少（UNLIMITED，永不阻塞读网络），
 * 下游一条一条念，顺序天然正确。
 *
 * 切句规则见 [feed]：句末标点断句，另外两个兜底保证「不会因为没等到句号而卡住」——
 *   · 攒够 [MAX_PENDING_CHARS] 还没断句 → 在最近的逗号处切开，没有逗号就硬切
 *   · 流结束时 [finish] 会把剩下的尾巴也念掉
 */
class SpeechQueue(
    private val speaker: TtsSpeaker,
    scope: CoroutineScope,
) {

    /**
     * 句子队列。UNLIMITED 是刻意的：`trySend` 永不挂起，
     * 所以「网络读得比播放快」时不会反过来把 pi 的流堵住。
     */
    private val sentences = Channel<String>(Channel.UNLIMITED)

    /**
     * 还没凑成整句的残留。
     *
     * 只有 [feed]（读流那个协程）和 [finish]（同一个协程的后续）会碰它，
     * 所以不需要加锁 —— [stop] 故意不动它，改由 [stopped] 让后续 feed 直接失效。
     */
    private val pending = StringBuilder()

    /** 被打断后为 true：后续 delta 一律丢弃，不再出声。 */
    @Volatile
    private var stopped = false

    /** 最近一次朗读失败的原因。消费者协程写、[finish] 在 join 之后读。 */
    private var lastError: String? = null

    private val job: Job = scope.launch {
        // 单消费者：顺序天然正确，不会出现两句同时开口。
        // 用 receiveCatching 而不是 for-in：队列关闭（正常收尾 / 被打断）就退出，
        // 不用管迭代器那套被弃用的写法。
        while (true) {
            val sentence = sentences.receiveCatching().getOrNull() ?: break
            if (stopped) break
            speaker.speak(sentence)?.let { lastError = it }
        }
    }

    /**
     * 喂一段**增量**文本。断出整句就立刻入队开始合成，不等后面的。
     */
    fun feed(delta: String) {
        if (stopped || delta.isEmpty()) return
        pending.append(delta)
        emitSentences()
        emitOverflow()
    }

    /**
     * 收尾：把没断句的尾巴也念掉，**等队列放完才返回**。
     * 返回最后一个朗读错误，null = 一切正常。
     */
    suspend fun finish(): String? {
        if (!stopped) {
            enqueue(pending.toString())
            pending.setLength(0)
        }
        sentences.close()
        job.join()
        return lastError
    }

    /**
     * 打断：立刻静音，并且之后收到的 delta 一律不念。
     *
     * 这里 `job.cancel()` 而不是只置标志：[stop] 可能正好发生在
     * 「刚查完标志、还没开始念下一句」的缝里，只置标志会让那一句漏出去；
     * 取消协程把这半个缝也关上了（正在播的那句会被播放器立刻打断）。
     */
    fun stop() {
        stopped = true
        speaker.stop()
        sentences.close()
        job.cancel()
    }

    // ------------------------------------------------------------------ 内部

    /** 把 [pending] 里所有完整句子切出去。 */
    private fun emitSentences() {
        var start = 0
        for (i in pending.indices) {
            if (pending[i] in SENTENCE_ENDS) {
                enqueue(pending.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start > 0) pending.delete(0, start)
    }

    /**
     * 兜底：一直没等到句末标点也不能无限等下去，否则用户听到的是「静默 → 突然一句长话」。
     * 优先在逗号/顿号处切（听感上是个自然停顿），实在没有就硬切。
     */
    private fun emitOverflow() {
        while (pending.length >= MAX_PENDING_CHARS) {
            val window = pending.substring(0, MAX_PENDING_CHARS)
            val soft = window.indexOfLast { it in SOFT_BREAKS }
            val cut = if (soft >= MIN_SOFT_CUT) soft + 1 else MAX_PENDING_CHARS
            enqueue(pending.substring(0, cut))
            pending.delete(0, cut)
        }
    }

    private fun enqueue(raw: String) {
        val sentence = raw.trim()
        if (sentence.isEmpty()) return
        sentences.trySend(sentence)
    }

    private companion object {
        /** 句末标点：中英文都收，另外把换行也当断点（段落之间本来就该停一下）。 */
        const val SENTENCE_ENDS = "。！？；…!?;\n"

        /** 可以当「软断点」的停顿符。 */
        const val SOFT_BREAKS = "，、,：: "

        /** 攒到这个长度还没断句就强制切一刀。48 字 ≈ 10 秒语音，再长用户会觉得卡。 */
        const val MAX_PENDING_CHARS = 48

        /** 软断点太靠前就别用了（切出个三五个字的小碎片反而更难听）。 */
        const val MIN_SOFT_CUT = 16
    }
}
