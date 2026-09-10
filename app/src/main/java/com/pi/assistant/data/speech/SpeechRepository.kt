package com.pi.assistant.data.speech

import android.content.Context
import android.util.Base64
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pi.assistant.data.prefs.AsrProtocol
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.data.prefs.TtsProtocol
import com.pi.assistant.util.MarkdownStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 语音端点调用的结果，异常一律不外泄。 */
sealed interface SpeechResult<out T> {
    data class Ok<T>(val value: T) : SpeechResult<T>
    data class Failed(val message: String) : SpeechResult<Nothing>
}

/**
 * 语音端点的入口。识别和朗读**各自**按自己的协议走，两家服务商可以是完全不同的
 * 请求形状（multipart / base64 JSON / chat 补全），所以这里按协议分发，而不是
 * 靠一个 baseUrl 猜。
 */
@Singleton
class SpeechRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    // ------------------------------------------------------------------ 识别

    /**
     * 传 16k/mono/16bit 的 WAV，拿识别文本。
     */
    suspend fun transcribe(wav: File): SpeechResult<String> = withContext(Dispatchers.IO) {
        val snapshot = settings.current
        if (!snapshot.asrConfigured) {
            return@withContext SpeechResult.Failed("ASR 端点未配置：去设置页把「识别」那一块的地址和模型填上")
        }
        if (!wav.exists() || wav.length() == 0L) {
            return@withContext SpeechResult.Failed("录音文件是空的")
        }

        when (val protocol = snapshot.asrProtocol) {
            AsrProtocol.OPENAI -> transcribeOpenAi(protocol, wav)
            AsrProtocol.BAILIAN_FUN_ASR -> transcribeBailian(wav)
        }
    }

    /** OpenAI 风格：multipart 把 wav 传上去。 */
    private suspend fun transcribeOpenAi(protocol: AsrProtocol, wav: File): SpeechResult<String> {
        val snapshot = settings.current
        val api = openAiApi(protocol, snapshot.asrBaseUrl, snapshot.asrToken)
            ?: return SpeechResult.Failed("ASR 地址不合法：${snapshot.asrBaseUrl}")

        val part = MultipartBody.Part.createFormData(
            "file",
            wav.name,
            wav.asRequestBody("audio/wav".toMediaType()),
        )

        val response = try {
            api.transcribe(
                file = part,
                model = snapshot.asrModel.toPlainBody(),
                language = snapshot.asrLanguage.takeIf { it.isNotBlank() }?.toPlainBody(),
                prompt = snapshot.asrPrompt.takeIf { it.isNotBlank() }?.toPlainBody(),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return SpeechResult.Failed(
                "ASR 请求失败 @${snapshot.asrBaseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            return SpeechResult.Failed(httpError("ASR", snapshot.asrBaseUrl, response.code(), response.errorBody()?.string()))
        }

        val text = response.body()?.text?.trim().orEmpty()
        return if (text.isEmpty()) SpeechResult.Failed("ASR 返回了空文本") else SpeechResult.Ok(text)
    }

    /**
     * 百炼：音频要以 base64 data URI 塞进 JSON。
     *
     * 文档明确说编码后要满足 10MB 上限 —— 我们的录音 16k/mono/16bit，
     * 就算录满 60 秒也才 1.9MB（base64 后 2.6MB），离上限很远，不用特殊处理。
     */
    private suspend fun transcribeBailian(wav: File): SpeechResult<String> {
        val snapshot = settings.current
        val api = bailianApi(snapshot.asrBaseUrl, snapshot.asrToken)
            ?: return SpeechResult.Failed("ASR 地址不合法：${snapshot.asrBaseUrl}")

        val base64 = try {
            Base64.encodeToString(wav.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return SpeechResult.Failed("读录音文件失败：${e.message ?: e::class.java.simpleName}")
        }

        val body = BailianAsrRequest(
            model = snapshot.asrModel,
            input = BailianInput(
                messages = listOf(
                    BailianMessage(
                        role = "user",
                        content = listOf(
                            BailianContent(
                                type = "input_audio",
                                input_audio = BailianAudio(data = "data:audio/wav;base64,$base64"),
                            )
                        ),
                    )
                )
            ),
            parameters = BailianParameters(
                format = "wav",
                sample_rate = BAILIAN_SAMPLE_RATE,
                language_hints = snapshot.asrLanguage.takeIf { it.isNotBlank() }?.let { listOf(it) },
            ),
        )

        val response = try {
            api.generate(sse = "disable", body = body)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return SpeechResult.Failed(
                "ASR 请求失败 @${snapshot.asrBaseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            return SpeechResult.Failed(httpError("ASR", snapshot.asrBaseUrl, response.code(), response.errorBody()?.string()))
        }

        // 百炼没有 choices，文字在 output.text；嵌套的 output.output.sentence.text 兜底
        val output = response.body()?.output
        val text = output?.text?.trim().orEmpty()
            .ifBlank { output?.output?.sentence?.text?.trim().orEmpty() }
        return if (text.isEmpty()) SpeechResult.Failed("ASR 返回了空文本") else SpeechResult.Ok(text)
    }

    // ------------------------------------------------------------------ 朗读

    /**
     * 文字转音频，返回落盘的临时文件。
     * 长文本先按句切分并发合成是二期的事，这里整段来，先保证能用。
     */
    suspend fun synthesize(text: String): SpeechResult<File> = withContext(Dispatchers.IO) {
        val snapshot = settings.current
        if (!snapshot.ttsConfigured) {
            val detail = if (snapshot.ttsShareAsr && snapshot.ttsShareAvailable) {
                "朗读现在和识别共用服务商，检查识别侧地址 + 朗读模型名"
            } else {
                "去设置页把「朗读」那一块的地址和模型填上"
            }
            return@withContext SpeechResult.Failed("TTS 端点未配置：$detail")
        }

        val plain = MarkdownStripper.strip(text)
        if (plain.isBlank()) return@withContext SpeechResult.Failed("这段内容没有可朗读的文字")

        // 兼容端点的输入上限差别很大，先截一刀，宁可少读也别整个 400
        val clipped = plain.take(MAX_TTS_CHARS)

        when (snapshot.ttsProtocol) {
            TtsProtocol.OPENAI -> synthesizeOpenAi(clipped)
            TtsProtocol.MIMO_CHAT -> synthesizeMimo(clipped)
        }
    }

    /** OpenAI 风格：响应体直接是音频字节。 */
    private suspend fun synthesizeOpenAi(text: String): SpeechResult<File> {
        val snapshot = settings.current
        val api = openAiApi(TtsProtocol.OPENAI, snapshot.ttsEffectiveBaseUrl, snapshot.ttsEffectiveToken)
            ?: return SpeechResult.Failed("TTS 地址不合法：${snapshot.ttsEffectiveBaseUrl}")

        val response = try {
            api.speech(
                OpenAiTtsRequest(
                    model = snapshot.ttsModel,
                    input = text,
                    voice = snapshot.ttsVoice,
                    response_format = snapshot.ttsFormat,
                    speed = snapshot.ttsSpeed,
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return SpeechResult.Failed(
                "TTS 请求失败 @${snapshot.ttsEffectiveBaseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            return SpeechResult.Failed(
                httpError("TTS", snapshot.ttsEffectiveBaseUrl, response.code(), response.errorBody()?.string())
            )
        }

        val body = response.body() ?: return SpeechResult.Failed("TTS 返回了空响应")
        val ext = snapshot.ttsFormat.lowercase().ifBlank { "mp3" }

        try {
            return writeAudio(ext) { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        } finally {
            runCatching { body.close() }
        }
    }

    /**
     * MiMo：走 chat/completions，音频是 base64 藏在 JSON 里。
     *
     * 两条容易踩的规则（照文档）：
     *   · 要合成的文本必须放 **assistant** 消息，放 user 会被当成指令、不出声
     *   · user 消息是可选的自然语言风格指令，用来控制语气/方言/语速
     */
    private suspend fun synthesizeMimo(text: String): SpeechResult<File> {
        val snapshot = settings.current
        val api = mimoApi(snapshot.ttsEffectiveBaseUrl, snapshot.ttsEffectiveToken)
            ?: return SpeechResult.Failed("TTS 地址不合法：${snapshot.ttsEffectiveBaseUrl}")

        val messages = buildList {
            // MiMo 没有数字化的语速参数，语速只能写进自然语言指令里，
            // 所以把 ttsSpeed 折算成一句话拼上去，别让这个设置项在 MiMo 下失效。
            val instruction = listOfNotNull(
                snapshot.ttsStylePrompt.trim().takeIf { it.isNotEmpty() },
                snapshot.ttsSpeed.toPaceHint(),
            ).joinToString("，")

            if (instruction.isNotEmpty()) add(MimoMessage(role = "user", content = instruction))
            add(MimoMessage(role = "assistant", content = text))
        }

        val response = try {
            api.tts(
                MimoTtsRequest(
                    model = snapshot.ttsModel,
                    messages = messages,
                    audio = MimoAudioSpec(
                        format = snapshot.ttsFormat.ifBlank { "wav" },
                        voice = snapshot.ttsVoice.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return SpeechResult.Failed(
                "TTS 请求失败 @${snapshot.ttsEffectiveBaseUrl.endpointLabel()}：${e.message ?: e::class.java.simpleName}"
            )
        }

        if (!response.isSuccessful) {
            return SpeechResult.Failed(
                httpError("TTS", snapshot.ttsEffectiveBaseUrl, response.code(), response.errorBody()?.string())
            )
        }

        val encoded = response.body()?.choices?.firstOrNull()?.message?.audio?.data.orEmpty()
        if (encoded.isBlank()) {
            return SpeechResult.Failed("TTS 响应里没有音频数据（模型或音色名可能不对）")
        }

        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return SpeechResult.Failed("音频 base64 解码失败：${e.message ?: e::class.java.simpleName}")
        }
        if (bytes.isEmpty()) return SpeechResult.Failed("TTS 返回 0 字节")

        return writeAudio(snapshot.ttsFormat.lowercase().ifBlank { "wav" }) { out -> out.write(bytes) }
    }

    // ------------------------------------------------------------------ 内部

    private data class ClientKey(val protocol: String, val baseUrl: String, val token: String)

    /**
     * 每个协议各持一份客户端缓存。
     *
     * 以前只有一份 cachedApi —— 一旦两侧指向不同服务商，每次调用都会把对方的
     * 实例顶掉、来回重建连接池。key 里也带上协议和地址：鉴权头按协议不同，
     * 换协议之后也不能复用旧实例。
     *
     * 注：识别和朗读都用 OpenAI 协议、且地址 token 一样时，共用同一个
     * openAiHolder 是安全的 —— 那种情况下客户端配置完全相同。
     */
    private class ApiHolder<T : Any> {
        @Volatile
        var key: ClientKey? = null

        @Volatile
        var api: T? = null
    }

    private val openAiHolder = ApiHolder<OpenAiSpeechApi>()
    private val bailianHolder = ApiHolder<BailianAsrApi>()
    private val mimoHolder = ApiHolder<MimoTtsApi>()

    private fun openAiApi(protocol: Any, rawBaseUrl: String, token: String): OpenAiSpeechApi? =
        apiFor(openAiHolder, protocol, rawBaseUrl, token) { it.create(OpenAiSpeechApi::class.java) }

    private fun bailianApi(rawBaseUrl: String, token: String): BailianAsrApi? =
        apiFor(bailianHolder, AsrProtocol.BAILIAN_FUN_ASR, rawBaseUrl, token) {
            it.create(BailianAsrApi::class.java)
        }

    private fun mimoApi(rawBaseUrl: String, token: String): MimoTtsApi? =
        apiFor(mimoHolder, TtsProtocol.MIMO_CHAT, rawBaseUrl, token) {
            it.create(MimoTtsApi::class.java)
        }

    private fun <T : Any> apiFor(
        holder: ApiHolder<T>,
        protocol: Any,
        rawBaseUrl: String,
        token: String,
        create: (Retrofit) -> T,
    ): T? {
        val base = normalize(protocol, rawBaseUrl)
        if (base.isBlank()) return null

        val key = ClientKey(protocol.toString(), base, token)
        holder.api?.takeIf { holder.key == key }?.let { return it }
        return synchronized(holder) {
            holder.api?.takeIf { holder.key == key }
                ?: create(build(base, token, protocol)).also {
                    holder.api = it
                    holder.key = key
                }
        }
    }

    /** 地址按协议规范化：百炼不能补 /v1，它的路径由接口声明去拼。 */
    private fun normalize(protocol: Any, raw: String): String = when (protocol) {
        is AsrProtocol -> SettingsStore.normalizeBaseFor(protocol, raw)
        is TtsProtocol -> SettingsStore.normalizeBaseFor(protocol, raw)
        else -> SettingsStore.normalizeOpenAiStyleBase(raw)
    }

    private fun build(baseUrl: String, token: String, protocol: Any): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 长音频识别和整段朗读都可能慢，给足时间
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                        // MiMo 的两份官方示例鉴权头不一致：curl 写的是 `api-key`，
                        // Python(OpenAI SDK) 走 Authorization。既然都可能，就两个都带上 ——
                        // 值相同不会冲突，也省得在真机上试错。
                        if (protocol == TtsProtocol.MIMO_CHAT) addHeader("api-key", token)
                    }
                }.build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
            .build()
    }

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
            SpeechResult.Failed("TTS 返回 0 字节")
        } else {
            SpeechResult.Ok(out)
        }
    }

    private fun httpError(what: String, baseUrl: String, code: Int, body: String?): String =
        "$what HTTP $code @${baseUrl.endpointLabel()}：${body?.take(200).orEmpty()}"

    /** 旧 TTS 缓存留着只会占地方。 */
    private fun cleanOldAudio() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("tts_") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    private fun String.toPlainBody(): RequestBody = toRequestBody(PLAIN_TEXT)

    /**
     * 报错时只显示 host —— 两侧可能是不同服务商，不标出来根本不知道是哪一边挂了；
     * 但完整 URL 又太长，塞进 toast 会看不清。
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
        val PLAIN_TEXT = "text/plain".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        const val MAX_TTS_CHARS = 4000
        const val BAILIAN_SAMPLE_RATE = "16000"
    }
}
