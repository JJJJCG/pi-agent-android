package com.pi.assistant.data.pi

import kotlinx.serialization.json.Json
import okio.BufferedSource

/**
 * `/v1/chat/stream` 的一帧，语义化之后的样子。
 *
 * 与 [PiStreamFrame]（线上字段）分开：那边是「端点说了什么」，这边是
 * 「对我们意味着什么」，中间的解码规则（比如 `started` 缺省算 false）
 * 只在这一个地方维护。
 */
sealed interface PiEvent {

    /** 本段**新增**文本（不是全文前缀）。 */
    data class Delta(val text: String) : PiEvent

    /** 收尾帧，字段与 `/v1/chat` 的 200 响应同义。 */
    data class Done(val reply: String, val tools: Int?, val ms: Long?) : PiEvent

    /**
     * 中途失败。
     *
     * [started] 是流式独有的判据：响应头在「注入成功」那一刻就已经发出去了，
     * 所以 504 / 503 这类**注入之后**的失败拿不到 HTTP 状态码，只能看这一位。
     */
    data class Failure(
        val code: String?,
        val message: String?,
        val httpStatus: Int?,
        val started: Boolean,
    ) : PiEvent
}

/**
 * 一帧的读取结果，用来区分「正常收尾」和「半路断了」。
 */
internal enum class StreamEnd {
    /** 收到 `data: [DONE]`，pi 明确说完了。 */
    Done,

    /** 连接被读完（正常 EOF 或中途断开）却没等到 `[DONE]`。 */
    Truncated,
}

/**
 * 从 [source] 逐帧读 SSE，把每帧交给 [onEvent]。
 *
 * 按 SSE 规范实现，不假设「一行一帧」：
 *   · 空行 = 一帧结束 —— 帧内可能有多行 `data:`，按 `\n` 拼接后再解析
 *   · `:` 开头 = 注释。pi 在静默期每 10 秒发一行 `: ping`，直接丢
 *   · `event:` / `id:` / `retry:` 我们不用，忽略
 *
 * 返回 [StreamEnd]，让调用方分清「pi 说完了」和「连接断了但没说完」——
 * 这两种情况的处理完全不同（前者落库，后者不能当成功）。
 */
internal suspend fun readPiEvents(
    source: BufferedSource,
    json: Json,
    onEvent: suspend (PiEvent) -> Unit,
): StreamEnd {
    val pending = StringBuilder()
    var end = StreamEnd.Truncated

    suspend fun flushFrame() {
        if (pending.isEmpty()) return
        val payload = pending.toString()
        pending.setLength(0)

        if (payload == DONE_SENTINEL) {
            end = StreamEnd.Done
            return
        }

        // 解析不了一帧就当它不存在：宁可少一句也不要为一帧坏 JSON 打断整轮朗读
        val frame = runCatching {
            json.decodeFromString(PiStreamFrame.serializer(), payload)
        }.getOrNull() ?: return

        when (frame.type) {
            "delta" -> frame.text
                ?.takeIf { it.isNotEmpty() }
                ?.let { onEvent(PiEvent.Delta(it)) }

            "done" -> onEvent(PiEvent.Done(frame.reply.orEmpty(), frame.tools, frame.ms))

            "error" -> onEvent(
                PiEvent.Failure(
                    code = frame.code,
                    message = frame.error,
                    httpStatus = frame.httpStatus,
                    // 缺字段按「没起过」处理，但下面还会用「有没有收到过 delta」
                    // 交叉验证，所以这里不是单点判断
                    started = frame.started == true,
                )
            )

            else -> Unit
        }
    }

    while (end != StreamEnd.Done) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> flushFrame()
            line.startsWith(":") -> Unit
            line.startsWith("data:") -> {
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(line.removePrefix("data:").trim())
            }
            else -> Unit
        }
    }
    // 最后一阵可能没等到空行就 EOF 了，补一次
    flushFrame()

    return end
}

private const val DONE_SENTINEL = "[DONE]"
