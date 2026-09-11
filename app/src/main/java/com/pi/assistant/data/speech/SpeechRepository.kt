package com.pi.assistant.data.speech

import android.content.Context
import android.util.Base64
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pi.assistant.data.net.HttpClients
import com.pi.assistant.data.prefs.MimoSpeech
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.util.MarkdownStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 语音调用结果，异常一律不外泄。 */
sealed interface SpeechResult<out T> {
    data class Ok<T>(val value: T) : SpeechResult<T>
    data class Failed(val message: String) : SpeechResult<Nothing>
}

/**
 * 流式合成专用的异常，`message` 是可以直接给用户看的一句话。
 *
 * 为什么这里破例让异常往外抛（而不是像 [SpeechResult] 那样包一层）：流式合成是一条
 * **冷流**，中途失败时前面几块音频已经播出去了 —— 这时候没有「返回一个失败对象」的位置，
 * 只能在收集处 catch。类型收窄成这一个，调用方不用认识 OkHttp / 序列化那一堆异常。
 */
class SpeechException(message: String) : Exception(message)

/**
 * 语音识别与合成，都走小米 MiMo。
 *
 * 两条链路共用同一个 `chat/completions` 端点和同一份 key，所以客户端只缓存一份。
 * OkHttpClient 从 [HttpClients] 的根 client 派生（A7），不再自己另起一套线程池。
 */
@Singleton
class SpeechRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val clients: HttpClients,
) {

    // ------------------------------------------------------------------ 识别

    /**
     * 传 16k/mono/16bit 的 WAV，拿识别文本。
     *
     * 音频要整个 base64 进 JSON —— MiMo 只收 wav / mp3，编码后上限 10MB。
     * 我们的录音算 60 秒也才 1.9MB（base64 后 2.6MB），离上限很远。
     */
    suspend fun transcribe(wav: File): SpeechResult<String> = withContext(Dispatchers.IO) {
        val speech = settings.current.speech
        if (!speech.asrConfigured) {
            return@withContext SpeechResult.Failed("语音未配置：去设置页填 MiMo 的 API Key")
        }
        if (!wav.exists() || wav.length() == 0L) {
            return@withContext SpeechResult.Failed("录音文件是空的")
        }

        val base64 = try {
            Base64.encodeToString(wav.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SpeechResult.Failed("读录音文件失败：${e.message ?: e::class.java.simpleName}")
        }

        val body = MimoAsrRequest(
            model = speech.asrModel,
            messages = listOf(
                MimoAsrMessage(
                    role = "user",
                    content = listOf(
                        // type 固定 input_audio，且 content 里不能再有别的类型
                        MimoAsrContent(
                            type = "input_audio",
                            input_audio = MimoInputAudio(data = "data:audio/wav;base64,$base64"),
                        )
                    ),
                )
            ),
            asr_options = MimoAsrOptions(
                language = speech.asrLanguage.ifBlank { "auto" },
            ),
        )

        val speechApi = api(speech)
            ?: return@withContext SpeechResult.Failed("MiMo 地址不合法：${speech.baseUrl}")

        val response = try {
            speechApi.transcribe(body)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SpeechResult.Failed(
                "识别请求失败 @${speech.baseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            return@withContext SpeechResult.Failed(
                httpError("识别", speech.baseUrl, response.code(), response.errorBody()?.string())
            )
        }

        val text = response.body()
            ?.choices?.firstOrNull()
            ?.message?.content?.trim().orEmpty()

        if (text.isEmpty()) {
            SpeechResult.Failed("识别返回了空文本（模型名或音频格式可能不对）")
        } else {
            SpeechResult.Ok(text)
        }
    }

    // ------------------------------------------------------------------ 朗读

    /**
     * 文字转音频，返回落盘的临时文件（整段合成）。
     *
     * 走这条路的场合：设置里关掉了流式（端点不认 `stream` 参数时的退路），
     * 以及流式「一个音都没出来」时的兜底。日常朗读走 [synthesizeStream]。
     */
    suspend fun synthesize(text: String): SpeechResult<File> = withContext(Dispatchers.IO) {
        val speech = settings.current.speech
        if (!speech.ttsConfigured) {
            return@withContext SpeechResult.Failed("语音未配置：去设置页填 MiMo 的 API Key")
        }

        val plain = MarkdownStripper.strip(text)
        if (plain.isBlank()) return@withContext SpeechResult.Failed("这段内容没有可朗读的文字")

        // 兼容端点的输入上限差别很大，先截一刀，宁可少读也别整个 400。
        // 流式那边同样截 —— 两个入口的字数上限要一致，否则「换个开关行为就变了」。
        val clipped = plain.take(MAX_TTS_CHARS)

        val speechApi = api(speech)
            ?: return@withContext SpeechResult.Failed("MiMo 地址不合法：${speech.baseUrl}")

        val response = try {
            speechApi.synthesize(buildTtsRequest(speech, clipped, stream = false))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SpeechResult.Failed(
                "合成请求失败 @${speech.baseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            return@withContext SpeechResult.Failed(
                httpError("合成", speech.baseUrl, response.code(), response.errorBody()?.string())
            )
        }

        val encoded = response.body()
            ?.choices?.firstOrNull()
            ?.message?.audio?.data.orEmpty()
        if (encoded.isBlank()) {
            return@withContext SpeechResult.Failed("响应里没有音频数据（模型名或音色可能不对）")
        }

        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SpeechResult.Failed("音频 base64 解码失败：${e.message ?: e::class.java.simpleName}")
        }
        if (bytes.isEmpty()) return@withContext SpeechResult.Failed("合成返回 0 字节")

        writeAudio(speech.ttsFormat.lowercase().ifBlank { "wav" }) { it.write(bytes) }
    }

    /**
     * 流式合成：边收边吐 PCM16 裸块（24kHz / 单声道 / 16bit）。
     *
     * 返回的是**冷流** —— 谁收集谁负责把它放出来（见
     * [com.pi.assistant.audio.PcmStreamPlayer]）。每读到一帧 SSE 就 emit 一块，
     * 中间不攒整段，所以首字延迟只取决于服务端吐第一块的速度。
     *
     * 背压天然存在：下游（播放器）写不进去就没人 collect，上游自然停在阻塞读上。
     *
     * @throws SpeechException 失败原因（给用户看的一句话）。取消等异常原样抛出。
     */
    fun synthesizeStream(text: String): Flow<ByteArray> = flow {
        val speech = settings.current.speech
        if (!speech.ttsConfigured) throw SpeechException("语音未配置：去设置页填 MiMo 的 API Key")

        val plain = MarkdownStripper.strip(text)
        if (plain.isBlank()) throw SpeechException("这段内容没有可朗读的文字")
        val clipped = plain.take(MAX_TTS_CHARS)

        val speechApi = streamApi(speech) ?: throw SpeechException("MiMo 地址不合法：${speech.baseUrl}")

        val response = try {
            speechApi.synthesizeStream(buildTtsRequest(speech, clipped, stream = true))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw SpeechException(
                "合成请求失败 @${speech.baseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            throw SpeechException(
                httpError("合成", speech.baseUrl, response.code(), response.errorBody()?.string())
            )
        }

        val body = response.body() ?: throw SpeechException("流式响应没有响应体")

        var received = 0
        var serverError: String? = null
        try {
            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                val payload = line.ssePayload() ?: continue
                if (payload == "[DONE]") break

                val frame = runCatching { JSON.parseToJsonElement(payload) }.getOrNull()
                    as? JsonObject ?: continue

                val encoded = frame.audioDataField()
                if (encoded != null) {
                    val pcm = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
                    if (pcm != null && pcm.isNotEmpty()) {
                        received += pcm.size
                        emit(pcm)
                    }
                }
                // 有的实现在流中间塞一个 error 帧（比如超长文本被拒）。留着它，
                // 万一整条流一个音都没出，报错才不至于只剩一句「没有音频数据」。
                serverError = frame.errorMessage() ?: serverError
            }
        } finally {
            // 中途被打断（用户点停）也要关：不关的话这条连接会一直挂着，
            // OkHttp 那边也会因为「响应体没读完」而不复用连接。
            runCatching { body.close() }
        }

        if (received == 0) {
            throw SpeechException(
                serverError?.let { "合成失败：$it" }
                    ?: "流式响应里没有音频数据（模型名、音色，或端点不支持 stream 都可能）"
            )
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ 内部

    /**
     * 组装流式 / 非流式共用的请求体 —— 两者只差 `format` 和 `stream` 两个字段。
     *
     * 两条容易踩的规则（照文档）：
     *   · 待合成文本必须放 **assistant** 消息，放 user 会被当成指令、不出声
     *   · user 消息是可选的自然语言风格指令，用来控制语气 / 方言 / 语速
     */
    private fun buildTtsRequest(speech: MimoSpeech, text: String, stream: Boolean): MimoTtsRequest {
        val messages = buildList {
            // MiMo 没有数字化的语速参数，语速只能写进自然语言指令里，
            // 所以把 ttsSpeed 折算成一句话拼上去，别让这个设置项在 MiMo 下失效。
            val instruction = listOfNotNull(
                speech.ttsStylePrompt.trim().takeIf { it.isNotEmpty() },
                speech.ttsSpeed.toPaceHint(),
            ).joinToString("，")

            if (instruction.isNotEmpty()) add(MimoChatMessage(role = "user", content = instruction))
            add(MimoChatMessage(role = "assistant", content = text))
        }

        return MimoTtsRequest(
            model = speech.ttsModel,
            messages = messages,
            audio = MimoAudioSpec(
                // 流式下格式是写死的：文档明确要求 pcm16，分块拼起来才是完整音频。
                // 所以「音频格式」这个设置项只在非流式下生效。
                format = if (stream) STREAM_FORMAT else speech.ttsFormat.ifBlank { "wav" },
                voice = speech.ttsVoice.trim().takeIf { it.isNotEmpty() },
            ),
            stream = stream.takeIf { it },
        )
    }

    private data class ClientKey(val baseUrl: String, val token: String)

    /** 一组「配置 → Retrofit」的缓存。两套客户端各一份，互不干扰。 */
    private class ApiCache {
        @Volatile
        var key: ClientKey? = null

        @Volatile
        var api: MimoSpeechApi? = null
    }

    private val plainCache = ApiCache()
    private val streamCache = ApiCache()

    /**
     * 整段合成用：给足 180s 总超时（长音频合成慢）。
     * 地址或 key 一变就重建 —— 设置页改完立刻生效，不用重启 App。
     */
    private fun api(speech: MimoSpeech): MimoSpeechApi? =
        apiFor(plainCache, speech) { base -> build(base, clients.forSpeech(speech.token)) }

    /**
     * 流式合成用：**不能设总超时**（见 [HttpClients.forSpeechStream]）。
     */
    private fun streamApi(speech: MimoSpeech): MimoSpeechApi? =
        apiFor(streamCache, speech) { base -> build(base, clients.forSpeechStream(speech.token)) }

    private fun apiFor(
        cache: ApiCache,
        speech: MimoSpeech,
        create: (String) -> MimoSpeechApi,
    ): MimoSpeechApi? {
        val base = SettingsStore.normalizeSpeechBaseUrl(speech.baseUrl)
        if (base.isBlank()) return null

        val key = ClientKey(base, speech.token)
        cache.api?.takeIf { cache.key == key }?.let { return it }
        return synchronized(cache) {
            cache.api?.takeIf { cache.key == key } ?: create(base).also {
                cache.api = it
                cache.key = key
            }
        }
    }

    private fun build(baseUrl: String, client: OkHttpClient): MimoSpeechApi =
        Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MimoSpeechApi::class.java)

    /** 音频统一落盘，顺手清掉旧的缓存文件。 */
    private fun writeAudio(ext: String, write: (OutputStream) -> Unit): SpeechResult<File> {
        cleanOldAudio()
        val out = File(context.cacheDir, "tts_${System.currentTimeMillis()}.$ext")
        try {
            out.outputStream().use { write(it) }
        } catch (e: Exception) {
            out.delete()
            if (e is CancellationException) throw e
            return SpeechResult.Failed("写音频文件失败：${e.message ?: e::class.java.simpleName}")
        }
        return if (out.length() == 0L) {
            out.delete()
            SpeechResult.Failed("合成返回 0 字节")
        } else {
            SpeechResult.Ok(out)
        }
    }

    private fun httpError(what: String, baseUrl: String, code: Int, body: String?): String =
        "$what HTTP $code @${baseUrl.endpointLabel()}：${body?.take(200).orEmpty()}"

    /** 旧 TTS 缓存留着只会占地方。流式不落盘，这条只服务非流式的兜底路径。 */
    private fun cleanOldAudio() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("tts_") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    /**
     * 报错时只显示 host —— 完整 URL 太长，塞进 toast 会看不清。
     */
    private fun String.endpointLabel(): String =
        substringAfter("://", this).substringBefore('/').ifBlank { this }

    /** 把 0.25~4.0 的语速折算成一句人话，给不认数字语速的 MiMo 用。 */
    private fun Float.toPaceHint(): String? = when {
        this <= 0.75f -> "语速放慢一些"
        this < 1.0f -> "语速稍慢"
        this >= 1.5f -> "语速明显加快"
        this > 1.05f -> "语速稍快"
        else -> null
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        const val MAX_TTS_CHARS = 4000

        /** 流式只认这个格式：官方文档要求 pcm16，分块拼起来才是完整音频。 */
        const val STREAM_FORMAT = "pcm16"
    }
}

// --------------------------------------------------------------- SSE 解析

/**
 * 一帧 SSE 行 → data 载荷。不是 `data:` 行（空行 / `:` 注释 / `event:`）返回 null。
 *
 * 行尾的 `\r` 由 `readUtf8Line()` 吃掉；这里再做一次 trim 是防中转端点手写响应
 * 时留下的空格。
 */
private fun String.ssePayload(): String? {
    val line = trim()
    if (!line.startsWith("data:")) return null
    return line.removePrefix("data:").trim().takeIf { it.isNotEmpty() }
}

/**
 * 从一帧 JSON 里挖出 base64 音频。
 *
 * 官方形态是 OpenAI 那套：`choices[0].delta.audio.data`。但中转 / 兼容端点花样不少 ——
 * 有的塞成非流式的 `choices[0].message.audio.data`，有的干脆挂在顶层 `audio` 下。
 * 所以不写死路径，改成「找名为 audio 的对象，取它下面那个叫 data 的字符串」，
 * 三种形态一次覆盖，以后字段位置再挪也不至于直接哑掉。
 */
private fun JsonObject.audioDataField(): String? {
    (this["audio"] as? JsonObject)?.dataString()?.let { return it }

    val choices = this["choices"] as? JsonArray ?: return null
    for (choice in choices) {
        val item = choice as? JsonObject ?: continue
        val holder = (item["delta"] as? JsonObject) ?: (item["message"] as? JsonObject) ?: continue
        val audio = holder["audio"] as? JsonObject ?: continue
        audio.dataString()?.let { return it }
    }
    return null
}

private fun JsonObject.dataString(): String? =
    (this["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** 一帧里的错误文案（`error` 是对象或裸字符串都认）。 */
private fun JsonObject.errorMessage(): String? = when (val err = this["error"]) {
    is JsonPrimitive -> err.content.takeIf { it.isNotBlank() }
    is JsonObject -> ((err["message"] ?: err["code"]) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    else -> null
}
