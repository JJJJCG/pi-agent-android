package com.pi.assistant.data.speech

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming

/**
 * 小米 MiMo 的语音接口。
 *
 * 识别和合成都挂在**同一个** `chat/completions` 上 —— 它复用的是 OpenAI 的聊天
 * 补全形态，不是 OpenAI 的 `/audio/transcriptions` 或 `/audio/speech`。
 * 调那两个端点会直接 404，所以别照 OpenAI 的习惯去猜。
 *
 * 字段名一律照官方文档原样抄（`input_audio` / `asr_options` / `asr_options.language`
 * 都是对端认的字符串），不美化、不改大小写。
 */
interface MimoSpeechApi {

    /** 语音识别：音频以 base64 data URI 放进 messages，文本从 message.content 取。 */
    @POST("chat/completions")
    suspend fun transcribe(@Body body: MimoAsrRequest): Response<MimoAsrResponse>

    /** 语音合成：待合成文本放 assistant 消息，音频 base64 在响应的 message.audio.data。 */
    @POST("chat/completions")
    suspend fun synthesize(@Body body: MimoTtsRequest): Response<MimoTtsResponse>

    /**
     * 流式合成：响应是 SSE，音频一小块一小块地吐（`delta.audio.data`，base64）。
     *
     * **`@Streaming` 是必须的。** 不带它的话 Retrofit 会先把整个响应体读进内存
     * 再交给调用方 —— 那样既拿不到「边到边播」，长音频还可能直接 OOM。
     */
    @Streaming
    @POST("chat/completions")
    suspend fun synthesizeStream(@Body body: MimoTtsRequest): Response<ResponseBody>
}

// ------------------------------------------------------------------- 语音识别

@Serializable
data class MimoAsrRequest(
    val model: String,
    val messages: List<MimoAsrMessage>,
    val asr_options: MimoAsrOptions,
)

@Serializable
data class MimoAsrMessage(
    val role: String,
    val content: List<MimoAsrContent>,
)

/**
 * 注意这里**只能有 input_audio**。
 *
 * 文档明确说 user 的 content 里混进 `text` 会直接报错（这点和一般多模态模型相反），
 * 所以没法顺手塞个提示词进去搜热词 —— 想提升专有名词识别率只能靠
 * `asr_options.language` 把语种定死，或者换个更合适的模型。
 */
@Serializable
data class MimoAsrContent(
    val type: String,
    val input_audio: MimoInputAudio? = null,
)

@Serializable
data class MimoInputAudio(
    /** `data:audio/wav;base64,...` 形式的 data URI。 */
    val data: String,
)

@Serializable
data class MimoAsrOptions(
    /** auto / zh / en。 */
    val language: String,
)

/**
 * 响应是标准的 chat.completion：识别文本在 `choices[0].message.content`。
 * `message.audio` 在这里是 null —— 那是 TTS 才有的字段。
 */
@Serializable
data class MimoAsrResponse(
    val choices: List<MimoAsrChoice> = emptyList(),
)

@Serializable
data class MimoAsrChoice(
    val message: MimoAsrMessageContent? = null,
)

@Serializable
data class MimoAsrMessageContent(
    val role: String? = null,
    val content: String? = null,
)

// ------------------------------------------------------------------- 语音合成

@Serializable
data class MimoTtsRequest(
    val model: String,
    val messages: List<MimoChatMessage>,
    val audio: MimoAudioSpec,
    /**
     * 流式开关。null = 不带这个字段（服务端按非流式处理）。
     * 序列化器配了 `explicitNulls = false`，所以 null 不会写进请求体。
     */
    val stream: Boolean? = null,
)

@Serializable
data class MimoChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class MimoAudioSpec(
    /**
     * 非流式：`wav` / `mp3`（拿到的是完整容器，直接落盘即可）。
     * 流式：必须是 `pcm16`（或 `pcm`，等价），否则分块拼起来是坏的。
     */
    val format: String,
    /** 预置音色 ID，如 `mimo_default`、`冰糖`；留空则用服务端默认。 */
    val voice: String? = null,
)

@Serializable
data class MimoTtsResponse(
    val choices: List<MimoTtsChoice> = emptyList(),
)

@Serializable
data class MimoTtsChoice(
    val message: MimoTtsMessage? = null,
)

@Serializable
data class MimoTtsMessage(
    val role: String? = null,
    val content: String? = null,
    /** 音频在这里：`data` 是 base64 编码的完整音频文件。 */
    val audio: MimoAudioPayload? = null,
)

@Serializable
data class MimoAudioPayload(
    val data: String = "",
)
