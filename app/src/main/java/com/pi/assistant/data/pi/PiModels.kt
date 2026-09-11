package com.pi.assistant.data.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `POST /v1/chat`（以及 `/v1/chat/stream`）请求体。
 * 对应文档：{"text": "必填", "timeout": 300}，timeout 默认 300，范围 1~3600。
 */
@Serializable
data class ChatRequest(
    val text: String,
    val timeout: Int = 300,
    /**
     * 场景提示，两个端点都认。`voice` = 这一问来自语音，
     * 回复会按口语稿来写（短、无代码块/表格/裸链接）—— 因为接下来要被念出来。
     */
    val hint: String? = null,
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
    /**
     * 能力位：是否支持 `/v1/chat/stream`。
     * 老版本 pi 不会返回这个字段 → null → 当作不支持，走整段模式。
     */
    val stream: Boolean? = null,
)

/**
 * `/v1/chat/stream` 的一帧（`data:` 那一行 JSON）。
 *
 * 三种 `type`：`delta`（增量文本）/ `done`（收尾，字段与整段模式同名同义）/
 * `error`（中途失败）。字段全部可空 —— 按 `type` 取用，缺字段不炸。
 */
@Serializable
data class PiStreamFrame(
    val type: String? = null,
    /** `delta`：**本段新增**的文本，不是全文前缀。 */
    val text: String? = null,
    /** `done`：所有 delta 拼起来的全文。 */
    val reply: String? = null,
    val tools: Int? = null,
    val ms: Long? = null,
    /** `error`：沿用与整段模式同一套错误码。 */
    val code: String? = null,
    val error: String? = null,
    @SerialName("httpStatus") val httpStatus: Int? = null,
    /**
     * `error`：pi 是否**已经产出过内容**。
     *
     * 这是流式独有的判据 —— 响应头是在「注入成功」那一刻才发的，所以 504/503
     * 这类注入之后才发生的失败只能以 error 帧到达（拿不到 HTTP 状态码）。
     * `false` = 一个 delta 都没发过，可以整体退回整段模式重来一次；
     * `true` = 已经吐过文本（这边可能已经念出来了），**绝不能重试**。
     */
    val started: Boolean? = null,
)
