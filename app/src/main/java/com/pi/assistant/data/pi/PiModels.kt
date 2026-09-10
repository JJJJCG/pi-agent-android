package com.pi.assistant.data.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /v1/chat` 请求体。
 * 对应文档：{"text": "必填", "timeout": 300}，timeout 默认 300，范围 1~3600。
 */
@Serializable
data class ChatRequest(
    val text: String,
    val timeout: Int = 300,
)

/**
 * `POST /v1/chat` 的 200 响应体。
 *
 * 失败时服务端也会返回同样结构的 JSON（带 code/error），但 HTTP 状态码非 200，
 * 所以这里把 code/error 一并声明出来，方便解析 errorBody。
 */
@Serializable
data class ChatReply(
    val ok: Boolean = false,
    val reply: String? = null,
    val tools: Int? = null,
    val ms: Long? = null,
    val code: String? = null,
    val error: String? = null,
)

/** `GET /v1/status` 响应体。 */
@Serializable
data class PiStatus(
    val ok: Boolean = false,
    val port: Int? = null,
    val busy: Boolean = false,
    val waiting: Boolean = false,
    val model: String? = null,
    val session: String? = null,
    @SerialName("sessionFile") val sessionFile: String? = null,
    val lastError: String? = null,
    val lastReplyPreview: String? = null,
)
