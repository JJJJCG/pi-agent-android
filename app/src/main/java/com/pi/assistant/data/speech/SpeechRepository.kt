package com.pi.assistant.data.speech

import android.content.Context
import android.util.Base64
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pi.assistant.data.prefs.MimoSpeech
import com.pi.assistant.data.prefs.SettingsStore
import com.pi.assistant.util.MarkdownStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 语音调用结果，异常一律不外泄。 */
sealed interface SpeechResult<out T> {
    data class Ok<T>(val value: T) : SpeechResult<T>
    data class Failed(val message: String) : SpeechResult<Nothing>
}

/**
 * 语音识别与合成，都走小米 MiMo。
 *
 * 两条链路共用同一个 `chat/completions` 端点和同一份 key，所以客户端只缓存一份。
 */
@Singleton
class SpeechRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
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
     * 文字转音频，返回落盘的临时文件。
     * 长文本先按句切分并发合成是二期的事，这里整段来，先保证能用。
     */
    suspend fun synthesize(text: String): SpeechResult<File> = withContext(Dispatchers.IO) {
        val speech = settings.current.speech
        if (!speech.ttsConfigured) {
            return@withContext SpeechResult.Failed("语音未配置：去设置页填 MiMo 的 API Key")
        }

        val plain = MarkdownStripper.strip(text)
        if (plain.isBlank()) return@withContext SpeechResult.Failed("这段内容没有可朗读的文字")

        // 兼容端点的输入上限差别很大，先截一刀，宁可少读也别整个 400
        val clipped = plain.take(MAX_TTS_CHARS)

        /*
         * 两条容易踩的规则（照文档）：
         *   · 待合成文本必须放 **assistant** 消息，放 user 会被当成指令、不出声
         *   · user 消息是可选的自然语言风格指令，用来控制语气 / 方言 / 语速
         */
        val messages = buildList {
            // MiMo 没有数字化的语速参数，语速只能写进自然语言指令里，
            // 所以把 ttsSpeed 折算成一句话拼上去，别让这个设置项在 MiMo 下失效。
            val instruction = listOfNotNull(
                speech.ttsStylePrompt.trim().takeIf { it.isNotEmpty() },
                speech.ttsSpeed.toPaceHint(),
            ).joinToString("，")

            if (instruction.isNotEmpty()) add(MimoChatMessage(role = "user", content = instruction))
            add(MimoChatMessage(role = "assistant", content = clipped))
        }

        val body = MimoTtsRequest(
            model = speech.ttsModel,
            messages = messages,
            audio = MimoAudioSpec(
                format = speech.ttsFormat.ifBlank { "wav" },
                voice = speech.ttsVoice.trim().takeIf { it.isNotEmpty() },
            ),
        )

        val speechApi = api(speech)
            ?: return@withContext SpeechResult.Failed("MiMo 地址不合法：${speech.baseUrl}")

        val response = try {
            speechApi.synthesize(body)
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

    // ------------------------------------------------------------------ 内部

    private data class ClientKey(val baseUrl: String, val token: String)

    @Volatile
    private var cachedKey: ClientKey? = null

    @Volatile
    private var cachedApi: MimoSpeechApi? = null

    /** 地址或 key 一变就重建 —— 设置页改完立刻生效，不用重启 App。 */
    private fun api(speech: MimoSpeech): MimoSpeechApi? {
        val base = SettingsStore.normalizeSpeechBaseUrl(speech.baseUrl)
        if (base.isBlank()) return null

        val key = ClientKey(base, speech.token)
        cachedApi?.takeIf { cachedKey == key }?.let { return it }
        return synchronized(this) {
            cachedApi?.takeIf { cachedKey == key }
                ?: build(base, speech.token).also {
                    cachedApi = it
                    cachedKey = key
                }
        }
    }

    private fun build(baseUrl: String, token: String): MimoSpeechApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 长音频识别和整段合成都可能慢，给足时间
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                        // MiMo 的两份官方示例鉴权头不一致：curl 写的是 `api-key`，
                        // Python(OpenAI SDK) 走 Authorization。既然都可能，就两个都带上 ——
                        // 值相同不会冲突，也省得在真机上试错。
                        addHeader("api-key", token)
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
            .create(MimoSpeechApi::class.java)
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
            SpeechResult.Failed("合成返回 0 字节")
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
    }
}
