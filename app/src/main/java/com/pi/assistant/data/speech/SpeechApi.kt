package com.pi.assistant.data.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming

/**
 * 语音相关的三套 HTTP 契约。
 *
 * 分成三个接口而不是一个：这几家的请求长相差得太远，塞进一个接口只会到处
 * 是「这个字段只有某家用」的注释。各自的字段名都照官方文档原样抄，不美化 ——
 * 上下划线、嵌套层级都是对端认的字符串，改一个字符就请求失败。
 */

// ---------------------------------------------------------------- OpenAI 风格

interface OpenAiSpeechApi {

    /**
     * POST {base}/audio/transcriptions —— multipart 上传，拿文字。
     * 文件名带 `.wav`：不少兼容端点是靠扩展名猜格式的。
     */
    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null,
        @Part("prompt") prompt: RequestBody? = null,
    ): Response<TranscriptionResponse>

    /**
     * POST {base}/audio/speech —— 传文字，响应体直接就是音频字节。
     * 字段名跟着 OpenAI 走：`response_format` / `speed` 是下划线，不是驼峰。
     *
     * @Streaming：别把音频整段缓冲进内存，调用方负责消费并关闭 body。
     */
    @Streaming
    @POST("audio/speech")
    suspend fun speech(@Body body: OpenAiTtsRequest): Response<ResponseBody>
}

@Serializable
data class TranscriptionResponse(
    val text: String = "",
)

@Serializable
data class OpenAiTtsRequest(
    val model: String,
    val input: String,
    val voice: String,
    val response_format: String = "mp3",
    val speed: Float = 1.0f,
)

// ------------------------------------------------------------------ 阿里云百炼

/**
 * 百炼 Fun-ASR-Flash / Qwen-Audio-3.0-ASR-Flash。
 *
 * 端点走 DashScope 的 multimodal-generation，**不是** OpenAI 那套
 * `/audio/transcriptions`：音频要以 base64 data URI 塞在 messages 里，
 * 响应也没有 `choices`，文字在 `output.text`。
 */
interface BailianAsrApi {

    /**
     * @param sse 固定传 "disable"。文档说「不传也只在推理完成后返回一次结果」，
     *            但显式关掉更省事，省得哪天服务端默认值变了，我们收到一串 SSE 事件流。
     */
    @POST("api/v1/services/aigc/multimodal-generation/generation")
    suspend fun generate(
        @Header("X-DashScope-SSE") sse: String,
        @Body body: BailianAsrRequest,
    ): Response<BailianAsrResponse>
}

@Serializable
data class BailianAsrRequest(
    val model: String,
    val input: BailianInput,
    val parameters: BailianParameters,
)

@Serializable
data class BailianInput(
    val messages: List<BailianMessage>,
)

@Serializable
data class BailianMessage(
    val role: String,
    val content: List<BailianContent>,
)

@Serializable
data class BailianContent(
    val type: String,
    /** type == "input_audio" 时必填。 */
    val input_audio: BailianAudio? = null,
)

@Serializable
data class BailianAudio(
    /** 公网可访问的音频 URL，或者 `data:audio/wav;base64,...`。 */
    val data: String,
)

@Serializable
data class BailianParameters(
    /** 必填：wav / mp3 / opus … */
    val format: String,
    /** 可选，我们固定 16k（录音就是 16k 单声道）。 */
    val sample_rate: String? = null,
    /** 可选，Fun-ASR-Flash 只认第一个。 */
    val language_hints: List<String>? = null,
)

/**
 * 百炼的响应结构和标准 DashScope 多模态格式**不一样**，注意这层嵌套：
 *
 * ```json
 * { "output": { "output": { "sentence": { "text": "…" } },
 *               "text": "识别结果" },
 *   "request_id": "…" }
 * ```
 *
 * `output.text` 是累积的完整文本，`output.output.sentence.text` 是当前这句；
 * 两边都取一下做兜底 —— 文档明确说没有 `choices` 字段。
 */
@Serializable
data class BailianAsrResponse(
    val output: BailianOutput? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class BailianOutput(
    val text: String = "",
    val output: BailianInnerOutput? = null,
)

@Serializable
data class BailianInnerOutput(
    val sentence: BailianSentence? = null,
)

@Serializable
data class BailianSentence(
    val text: String = "",
)

// ------------------------------------------------------------------- 小米 MiMo

/**
 * 小米 MiMo 的语音合成。
 *
 * 注意它**不是** OpenAI 的 `/audio/speech` —— 虽然 base_url 和 SDK 用法都
 * 看着像 OpenAI：它其实走的是 chat/completions，把音频 base64 塞在返回的
 * JSON 里。另外两条容易踩的规则：
 *
 *   1. 要合成的文本必须放在 **assistant** 消息里，不能放 user。
 *   2. user 消息是可选的自然语言风格指令（也控制语速），内容不会被读出来。
 */
interface MimoTtsApi {

    @POST("chat/completions")
    suspend fun tts(@Body body: MimoTtsRequest): Response<MimoTtsResponse>
}

@Serializable
data class MimoTtsRequest(
    val model: String,
    val messages: List<MimoMessage>,
    val audio: MimoAudioSpec,
)

@Serializable
data class MimoMessage(
    val role: String,
    val content: String,
)

@Serializable
data class MimoAudioSpec(
    /** wav / pcm16 …（流式才要求 pcm16，我们用非流式） */
    val format: String,
    /** 预置音色 ID，如 `mimo_default`、`冰糖`；留空则用服务端默认。 */
    val voice: String? = null,
)

@Serializable
data class MimoTtsResponse(
    val choices: List<MimoChoice> = emptyList(),
)

@Serializable
data class MimoChoice(
    val message: MimoResponseMessage? = null,
)

@Serializable
data class MimoResponseMessage(
    val role: String? = null,
    val content: String? = null,
    /** 音频在这里：`audio.data` 是 base64 字符串。 */
    val audio: MimoAudioPayload? = null,
)

@Serializable
data class MimoAudioPayload(
    val data: String = "",
)
