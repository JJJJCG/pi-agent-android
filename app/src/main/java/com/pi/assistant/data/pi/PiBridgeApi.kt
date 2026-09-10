package com.pi.assistant.data.pi

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * pi HTTP bridge 的三个端点，一个不多一个不少。
 *
 * 返回 [Response] 而不是裸类型，因为我们要读 HTTP 状态码做错误状态机
 * （401 / 409 / 503 / 504 的语义各不相同）。
 */
interface PiBridgeApi {

    /** 唯一的对话入口。同步阻塞直到 pi 答完；没有 SSE / 轮询 / 回调。 */
    @POST("/v1/chat")
    suspend fun chat(@Body body: ChatRequest): Response<ChatReply>

    /** 结构化状态，用于发送前避忙、504 后判断是否能重发。 */
    @GET("/v1/status")
    suspend fun status(): Response<PiStatus>

    /** Debug 页要展示原始 JSON，所以再要一份不解析的。 */
    @GET("/v1/status")
    suspend fun statusRaw(): Response<ResponseBody>

    /** 探活，纯文本 `ok`，免鉴权。 */
    @GET("/healthz")
    suspend fun health(): Response<ResponseBody>
}
