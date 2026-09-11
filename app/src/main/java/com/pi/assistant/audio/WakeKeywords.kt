package com.pi.assistant.audio

import android.content.Context
import android.util.Log
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * 把设置里的「唤醒词」实时转成 sherpa-onnx 的关键词文本。
 *
 * 词表格式与 assets/kws/keywords.txt 完全一致：
 *   `x iǎo p ài t óng x ué @小派同学`
 * 声母一个 token、带调韵母一个 token；零声母字（如「爱 ài」）整个音节就是
 * 一个 token。可用音素集合以 assets/kws/tokens.txt 为准 —— 转出来的每个
 * token 都必须能在里面找到，找不到就放弃该词，全部失败则返回 null，
 * 由调用方回退打包词表兜底。
 *
 * 为什么不直接改打包的 keywords.txt：那份是死的，改词就得重打安装包。
 * 现在走 KeywordSpotter.createStream(keywords) 的按流词表 —— 每次建流
 * 传入这份文本，模型就只听这几个词，设置里改完立即生效。
 */
object WakeKeywords {

    private const val TAG = "WakeKeywords"

    /** 声母表。双声母必须排在单声母前面，否则 zh 会被拆成 z + h。 */
    private val INITIALS = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x",
        "r", "z", "c", "s", "y", "w",
    )

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        // pinyin4j 的规矩：toneType=WITH_TONE_MARK 必须配 vCharType=WITH_V，
        // 否则抛 BadHanyuPinyinOutputFormatCombination
        vCharType = HanyuPinyinVCharType.WITH_V
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
    }

    @Volatile private var tokensCache: Set<String>? = null
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedText: String? = null

    /**
     * 生成按流关键词文本；null 表示转不出来，用打包词表兜底。
     * 结果按原文缓存 —— 监听循环每轮建流都会来取，别每次都跑拼音转换。
     */
    @Synchronized
    fun forSettings(context: Context, raw: String): String? {
        if (cachedRaw == raw) return cachedText
        val text = build(context, raw)
        cachedRaw = raw
        cachedText = text
        return text
    }

    private fun build(context: Context, raw: String): String? {
        val words = raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val tokens = tokens(context)
        if (tokens.isEmpty()) return null

        val lines = words.mapNotNull { word -> lineFor(word, tokens) }
        if (lines.isEmpty()) {
            Log.w(TAG, "唤醒词「$raw」一个都转不出拼音，回退打包词表")
            return null
        }
        Log.i(TAG, "按流唤醒词生效（${lines.size} 个）")
        return lines.joinToString("\n")
    }

    /** 单个词 → 一行 `token token … @词`；任何一个字转不出来就整个词放弃。 */
    private fun lineFor(word: String, tokens: Set<String>): String? {
        val parts = mutableListOf<String>()
        for (ch in word) {
            if (ch.isWhitespace()) continue
            val syllable = pinyinOf(ch) ?: return null
            val (initial, final) = splitSyllable(syllable)
            if (final.isEmpty()) return null
            if (initial.isNotEmpty()) {
                if (initial !in tokens) return null
                parts += initial
            }
            val finalToken = normalizeUmlaut(final)
            if (finalToken !in tokens) return null
            parts += finalToken
        }
        if (parts.isEmpty()) return null
        return "${parts.joinToString(" ")} @$word"
    }

    /**
     * 单字 → 带调拼音；非汉字或该字没有标注读音时返回 null。
     * 多音字取 pinyin4j 给的第一个（最常见）读音 —— 读错了就换一种写法或
     * 换个同音字，这是纯客户端方案固有的取舍。
     */
    private fun pinyinOf(ch: Char): String? = runCatching {
        PinyinHelper.toHanyuPinyinStringArray(ch, format)?.firstOrNull()
    }.getOrNull()

    private fun splitSyllable(syllable: String): Pair<String, String> {
        for (initial in INITIALS) {
            if (syllable.startsWith(initial)) {
                return initial to syllable.substring(initial.length)
            }
        }
        return "" to syllable
    }

    /** 本模型的 token 表把 ü 归一成 u（绿 lǜ → l ù），没有 ǖǘǚǜ 这几个码位。 */
    private fun normalizeUmlaut(final: String): String = buildString(final.length) {
        for (c in final) {
            append(
                when (c) {
                    'ǖ' -> 'ū'
                    'ǘ' -> 'ú'
                    'ǚ' -> 'ǔ'
                    'ǜ' -> 'ù'
                    'ü' -> 'u'
                    else -> c
                }
            )
        }
    }

    /** tokens.txt 的 token 集合（每行首列）。assets 不变，进程内缓存一次即可。 */
    private fun tokens(context: Context): Set<String> =
        tokensCache ?: run {
            val loaded = runCatching {
                context.assets.open("kws/tokens.txt").bufferedReader().useLines { lines ->
                    lines.mapTo(mutableSetOf()) { it.trim().substringBefore(' ') }
                }
            }.getOrDefault(emptySet())
            tokensCache = loaded
            loaded
        }
}
