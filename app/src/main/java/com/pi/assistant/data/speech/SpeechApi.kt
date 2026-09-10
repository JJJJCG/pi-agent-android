package com.pi.assistant.data.speech

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming

/**
 * OpenAI 兼容的语音端点。
 *
 * 只依赖两个路径，不依赖任何厂商私有的东西：
 *   POST {base}/audio/transcriptions   —— 传 wav，拿文字
 *   POST {base}/audio/speech           —— 传文字，拿音频二进制
 *
 * base 约定带 `/v1`（设置页会自动补）。
 */
interface SpeechApi {

    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null,
        @Part("prompt") prompt: RequestBody? = null,
    ): Response<TranscriptionResponse>

    @Streaming
    @POST("audio/speech")
    suspend fun speech(@Body body: TtsRequest): Response<ResponseBody>
}

@Serializable
data class TranscriptionResponse(
    val text: String = "",
)

/**
 * 字段名跟着 OpenAI 走（`response_format` / `speed` 是下划线，不是驼峰），
 * 兼容端点基本都认这一套。
 */
@Serializable
data class TtsRequest(
    val model: String,
    val input: String,
    val voice: String,
    val response_format: String = "mp3",
    val speed: Float = 1.0f,
)
