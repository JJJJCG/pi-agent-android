package com.pi.assistant.audio

import android.content.Context
import android.util.Log

/**
 * 探一下 native 库在不在。
 *
 * 这个项目**不强制**依赖 sherpa-onnx：拿不到 .so 时，VAD 和唤醒自动失效、
 * 界面明确说明原因，但 M1 的文本对话、以及走云端的 ASR/TTS 全都照常工作。
 * 所以整个 App 永远能装能跑，不会因为缺个库就崩。
 */
object SherpaNative {

    private const val TAG = "SherpaNative"
    private const val LIB = "sherpa-onnx-jni"

    val available: Boolean = runCatching { System.loadLibrary(LIB) }
        .onFailure { Log.w(TAG, "加载 $LIB 失败，端侧 VAD / 唤醒不可用", it) }
        .isSuccess

    /** 给 UI 直接显示的原因文案。 */
    val reason: String? = if (available) null else "缺少 libsherpa-onnx-jni.so，跑 tools/fetch_assets.py --native"
}

/**
 * assets 里模型文件的定位。
 *
 * 关键词模型刻意**按前缀发现**而不是写死文件名：上游每次发版，
 * encoder/decoder/joiner 后面都挂着 `-epoch-12-avg-2-chunk-16-left-64` 这种后缀，
 * 硬编码文件名注定过一阵就失效。
 */
object VoiceAssets {

    const val VAD_MODEL = "silero_vad.onnx"
    const val KWS_DIR = "kws"

    /** 实际解析出来的关键词模型文件路径。 */
    data class KwsPaths(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val keywords: String,
    )

    fun exists(context: Context, path: String): Boolean =
        runCatching { context.assets.open(path).use { true } }.getOrDefault(false)

    fun vadReady(context: Context): Boolean = exists(context, VAD_MODEL)

    fun kwsReady(context: Context): Boolean = resolveKws(context) != null

    fun resolveKws(context: Context): KwsPaths? {
        val files = listKwsDir(context).ifEmpty { return null }

        fun pick(prefix: String): String? = files
            .filter { it.startsWith(prefix, ignoreCase = true) && it.endsWith(".onnx", ignoreCase = true) }
            .sorted()
            .firstOrNull()
            ?.let { "$KWS_DIR/$it" }

        return KwsPaths(
            encoder = pick("encoder") ?: return null,
            decoder = pick("decoder") ?: return null,
            joiner = pick("joiner") ?: return null,
            tokens = files.firstOrNull { it.startsWith("tokens", ignoreCase = true) }
                ?.let { "$KWS_DIR/$it" } ?: return null,
            keywords = pickKeywords(files) ?: return null,
        )
    }

    /**
     * 模型包里同时有 `keywords.txt` 和 `keywords_raw.txt`（后者是给 text2token 用的原料），
     * 两者都能匹配 `keywords*`，所以必须显式排掉 raw —— 否则 native 侧会拿到一份
     * 未转成音素的文本，唤醒词永远匹配不上。
     */
    private fun pickKeywords(files: List<String>): String? {
        files.firstOrNull { it.equals("keywords.txt", ignoreCase = true) }?.let { return "$KWS_DIR/$it" }
        return files.firstOrNull {
            it.startsWith("keywords", ignoreCase = true) &&
                it.endsWith(".txt", ignoreCase = true) &&
                !it.contains("raw", ignoreCase = true)
        }?.let { "$KWS_DIR/$it" }
    }

    /** 缺哪些文件 —— 直接列给用户，比一句「初始化失败」有用得多。 */
    fun missingForKws(context: Context): List<String> {
        val files = listKwsDir(context)
        if (files.isEmpty()) return listOf("$KWS_DIR/ 目录是空的")

        val missing = mutableListOf<String>()
        if (files.none { it.startsWith("encoder", true) && it.endsWith(".onnx", true) }) missing += "encoder*.onnx"
        if (files.none { it.startsWith("decoder", true) && it.endsWith(".onnx", true) }) missing += "decoder*.onnx"
        if (files.none { it.startsWith("joiner", true) && it.endsWith(".onnx", true) }) missing += "joiner*.onnx"
        if (files.none { it.startsWith("tokens", true) }) missing += "tokens.txt"
        if (pickKeywords(files) == null) missing += "keywords.txt"
        return missing
    }

    private fun listKwsDir(context: Context): List<String> =
        runCatching { context.assets.list(KWS_DIR)?.filterNotNull()?.toList().orEmpty() }
            .getOrDefault(emptyList())
}
