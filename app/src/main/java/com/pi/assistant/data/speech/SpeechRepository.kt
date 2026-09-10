package com.pi.assistant.data.speech

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 语音端点调用的结果，异常一律不外泄。 */
sealed interface SpeechResult<out T> {
    data class Ok<T>(val value: T) : SpeechResult<T>
    data class Failed(val message: String) : SpeechResult<Nothing>
}

@Singleton
class SpeechRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * 传 16k/mono/16bit 的 WAV，拿识别文本。
     * 文件名带 `.wav` 扩展名 —— 不少兼容端点是靠扩展名猜格式的。
     */
    suspend fun transcribe(wav: File): SpeechResult<String> = withContext(Dispatchers.IO) {
        val snapshot = settings.current
        val api = api(snapshot)
            ?: return@withContext SpeechResult.Failed("语音端点未配置：先去设置页填地址和 ASR 模型")
        if (!wav.exists() || wav.length() == 0L) {
            return@withContext SpeechResult.Failed("录音文件是空的")
        }

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
            return@withContext SpeechResult.Failed("ASR 请求失败：${e.message ?: e::class.java.simpleName}")
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.errorBody()?.string() }.getOrNull()
            return@withContext SpeechResult.Failed(
                "ASR HTTP ${response.code()}：${body?.take(200).orEmpty()}"
            )
        }

        val text = response.body()?.text?.trim().orEmpty()
        if (text.isEmpty()) SpeechResult.Failed("ASR 返回了空文本") else SpeechResult.Ok(text)
    }

    /**
     * 文字转音频，返回落盘的临时文件。
     * 长文本先按句切分并发合成是二期的事，这里整段来，先保证能用。
     */
    suspend fun synthesize(text: String): SpeechResult<File> = withContext(Dispatchers.IO) {
        val snapshot = settings.current
        val api = api(snapshot)
            ?: return@withContext SpeechResult.Failed("语音端点未配置：先去设置页填地址和 TTS 模型")

        val plain = MarkdownStripper.strip(text)
        if (plain.isBlank()) return@withContext SpeechResult.Failed("这段内容没有可朗读的文字")

        val response = try {
            api.speech(
                TtsRequest(
                    model = snapshot.ttsModel,
                    // 兼容端点的输入上限差别很大，先截一刀，宁可少读也别整个 400
                    input = plain.take(MAX_TTS_CHARS),
                    voice = snapshot.ttsVoice,
                    response_format = snapshot.ttsFormat,
                    speed = snapshot.ttsSpeed,
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext SpeechResult.Failed("TTS 请求失败：${e.message ?: e::class.java.simpleName}")
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.errorBody()?.string() }.getOrNull()
            return@withContext SpeechResult.Failed(
                "TTS HTTP ${response.code()}：${body?.take(200).orEmpty()}"
            )
        }

        val body = response.body() ?: return@withContext SpeechResult.Failed("TTS 返回了空响应")
        val ext = snapshot.ttsFormat.lowercase().ifBlank { "mp3" }
        cleanOldAudio()
        val out = File(context.cacheDir, "tts_${System.currentTimeMillis()}.$ext")

        try {
            body.byteStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            out.delete()
            return@withContext SpeechResult.Failed("写音频文件失败：${e.message ?: e::class.java.simpleName}")
        } finally {
            runCatching { body.close() }
        }

        if (out.length() == 0L) {
            out.delete()
            SpeechResult.Failed("TTS 返回 0 字节")
        } else {
            SpeechResult.Ok(out)
        }
    }

    // ------------------------------------------------------------------ 内部

    private data class ClientKey(val baseUrl: String, val token: String)

    @Volatile
    private var cachedKey: ClientKey? = null

    @Volatile
    private var cachedApi: SpeechApi? = null

    private fun api(snapshot: PiSettings): SpeechApi? {
        if (!snapshot.speechConfigured) return null
        val base = SettingsStore.normalizeSpeechBaseUrl(snapshot.speechBaseUrl)
        if (base.isBlank()) return null

        val key = ClientKey(base, snapshot.speechToken)
        cachedApi?.takeIf { cachedKey == key }?.let { return it }
        return synchronized(this) {
            cachedApi?.takeIf { cachedKey == key }
                ?: build(base, snapshot.speechToken).also {
                    cachedApi = it
                    cachedKey = key
                }
        }
    }

    private fun build(baseUrl: String, token: String): SpeechApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(JSON.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SpeechApi::class.java)
    }

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

    private companion object {
        val PLAIN_TEXT = "text/plain".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        const val MAX_TTS_CHARS = 4000
    }
}
