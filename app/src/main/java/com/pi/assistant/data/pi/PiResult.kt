package com.pi.assistant.data.pi

/**
 * 一次 `POST /v1/chat` 的语义化结果。
 *
 * 这是 M1 的灵魂：把 HTTP 状态码翻译成 UI 能直接用的状态，
 * 让界面层永远不用去 parse 状态码。
 */
sealed interface PiResult {

    /** 200 且 ok=true。tools 是本次工具调用次数，ms 是服务端耗时。 */
    data class Ok(val reply: String, val tools: Int?, val ms: Long?) : PiResult

    /**
     * 504 且 `/v1/status.busy == true`。
     * pi 还在跑这一轮，**绝对不能给重发按钮**，越点越堆。
     */
    data class StillRunning(val hint: String) : PiResult

    /** 401：token 缺失或不对，去设置页重新填。 */
    data object AuthError : PiResult

    /** 503 或连不上。retryable=true 时值得让用户手动重试一次。 */
    data class Unavailable(val retryable: Boolean) : PiResult

    /** 其它非 200，带上服务端给的 code / error。 */
    data class Failed(val httpCode: Int, val code: String?, val msg: String?) : PiResult

    /** 本地就没配地址，请求根本没发出去。 */
    data class NotConfigured(val what: String) : PiResult
}

/** 服务端对一次请求的最终判定，供 Debug 页展示。 */
data class CallRecord(
    val at: Long,
    val httpCode: Int?,
    val code: String?,
    val resultLabel: String,
    val ms: Long,
    val note: String?,
    val errorBody: String?,
)
