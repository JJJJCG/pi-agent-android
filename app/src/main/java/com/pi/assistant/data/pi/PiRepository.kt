package com.pi.assistant.data.pi

import android.os.SystemClock
import com.pi.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 探活结果。 */
sealed interface ProbeResult {
    data class Ok(val ms: Long, val text: String) : ProbeResult
    data class Fail(val message: String, val ms: Long) : ProbeResult
}

@Singleton
class PiRepository @Inject constructor(
    private val factory: PiClientFactory,
    private val settings: SettingsStore,
) {
    private val _lastCall = MutableStateFlow<CallRecord?>(null)

    /** 最近一次请求的原始判定，Debug 页直接展示。 */
    val lastCall: StateFlow<CallRecord?> = _lastCall.asStateFlow()

    /** `/v1/status` 能力位（是否支持流式）的缓存，见 [supportsStream]。 */
    @Volatile
    private var streamCapable: Boolean? = null

    @Volatile
    private var streamProbedAt = 0L

    /**
     * 问一句，阻塞拿回答。所有异常都在这里被翻译成 [PiResult]，绝不外泄。
     *
     * 状态机（照 pi-http-bridge 调用文档写死）：
     *  200 ok=true      → Ok
     *  200 ok=false     → Failed（空回复）
     *  401              → AuthError（去设置页换 token）
     *  409 inject_rejected → 重试一次
     *  503 shutdown     → 等 3s 重试一次
     *  504 timeout      → **不重发**，先查 /v1/status.busy
     *  IO 异常           → Unavailable
     */
    suspend fun ask(
        text: String,
        timeoutSec: Int = settings.current.timeoutSec,
    ): PiResult {
        val snapshot = settings.current
        if (!snapshot.isConfigured) return PiResult.NotConfigured("pi 地址")
        return withContext(Dispatchers.IO) { attempt(text, timeoutSec, retriesLeft = 1) }
    }

    private suspend fun attempt(text: String, timeoutSec: Int, retriesLeft: Int): PiResult {
        val started = SystemClock.elapsedRealtime()
        val response = try {
            factory.api().chat(ChatRequest(text = text, timeout = timeoutSec))
        } catch (e: IOException) {
            val ms = SystemClock.elapsedRealtime() - started
            record(null, null, "Unavailable", ms, "网络异常，请确认 pi 已启动且与手机同网段", e.message)
            return PiResult.Unavailable(retryable = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val ms = SystemClock.elapsedRealtime() - started
            record(null, "client_error", "Failed", ms, "客户端异常", e.message)
            return PiResult.Failed(0, "client_error", e.message ?: e::class.java.simpleName)
        }

        val ms = SystemClock.elapsedRealtime() - started
        val body = response.body()

        return when (response.code()) {
            200 -> {
                if (body?.ok == true && body.reply != null) {
                    record(200, null, "Ok", ms, "tools=${body.tools} serverMs=${body.ms}", null)
                    PiResult.Ok(body.reply, body.tools, body.ms)
                } else {
                    val msg = body?.error ?: "空回复（ok 不为 true 或 reply 为 null）"
                    record(200, body?.code, "Failed", ms, msg, null)
                    PiResult.Failed(200, body?.code, msg)
                }
            }

            401 -> {
                record(401, null, "AuthError", ms, "token 缺失或不对", null)
                PiResult.AuthError
            }

            409 -> {
                val raw = readErrorBody(response)
                val (code, err) = parseError(raw)
                if (retriesLeft > 0) {
                    record(409, code, "Retry", ms, "注入被拒，300ms 后重试一次", raw)
                    delay(300)
                    attempt(text, timeoutSec, retriesLeft - 1)
                } else {
                    record(409, code, "Failed", ms, err ?: "注入被拒", raw)
                    PiResult.Failed(409, code, err ?: "注入被拒")
                }
            }

            503 -> {
                val raw = readErrorBody(response)
                val (code, err) = parseError(raw)
                if (retriesLeft > 0) {
                    record(503, code, "Retry", ms, "pi 正在换会话，3s 后重试一次", raw)
                    delay(3000)
                    attempt(text, timeoutSec, retriesLeft - 1)
                } else {
                    record(503, code, "Failed", ms, err ?: "pi 正在换会话", raw)
                    PiResult.Failed(503, code, err ?: "pi 正在换会话")
                }
            }

            504 -> {
                // 重点：504 只取消了「等待」，pi 仍在跑。先看 busy 再决定，绝不无脑重发。
                val busy = statusOrNull()?.busy ?: true
                if (busy) {
                    record(504, "timeout", "StillRunning", ms, "504 且 busy=true，不重发", null)
                    PiResult.StillRunning("pi 还在跑这一轮，先别重发；等它空闲后你再决定")
                } else {
                    record(504, "timeout", "Unavailable", ms, "504 且 busy=false，可手动重发", null)
                    PiResult.Unavailable(retryable = true)
                }
            }

            else -> {
                val raw = readErrorBody(response)
                val (code, err) = parseError(raw)
                record(response.code(), code, "Failed", ms, err, raw)
                PiResult.Failed(response.code(), code, err)
            }
        }
    }

    // ------------------------------------------------------------ 流式对话

    /**
     * 流式问一句：pi 一边写，[onDelta] 一边收到**增量**文本。
     *
     * 返回的仍是 [PiResult]，和 [ask] 同构 —— 上层（VoiceSession）沿用同一套错误
     * 分支，不必为流式再写一遍状态机。
     *
     * @param voiceTurn 这一问来自语音：带上 `hint=voice`，让 pi 按口语稿回
     * @param onDelta 增量回调（是**新增**的文本，不是全文）；在读取协程里执行，别做重活
     */
    suspend fun askStream(
        text: String,
        timeoutSec: Int = settings.current.timeoutSec,
        voiceTurn: Boolean = false,
        onDelta: suspend (String) -> Unit = {},
    ): PiResult {
        if (!settings.current.isConfigured) return PiResult.NotConfigured("pi 地址")
        // 老 pi 没有这个端点：直接走整段，别白试一次 404
        if (!supportsStream()) return ask(text, timeoutSec)
        return withContext(Dispatchers.IO) { streamOnce(text, timeoutSec, voiceTurn, onDelta) }
    }

    /**
     * pi 是否支持 `/v1/chat/stream`（`/v1/status` 的能力位）。
     *
     * 结果缓存 [STREAM_CAP_TTL_MS] —— 能力位是「pi 版本」的属性，不会来回变。
     * 而且每次 status 查询都会顺带刷新它（见 [statusOrNull]），
     * 所以界面进聊天页那次 `refreshStatus()` 已经把缓存捂热，这里通常不发请求。
     */
    suspend fun supportsStream(): Boolean {
        val cached = streamCapable
        val fresh = SystemClock.elapsedRealtime() - streamProbedAt < STREAM_CAP_TTL_MS
        if (cached != null && fresh) return cached

        return withContext(Dispatchers.IO) {
            val capable = try {
                factory.api().status().body()?.stream == true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 探测本身失败：按不支持处理，走整段模式总不会错
                false
            }
            streamCapable = capable
            streamProbedAt = SystemClock.elapsedRealtime()
            capable
        }
    }

    /**
     * 跑一次流式请求。与 [attempt] 的分工：这里只管流，遇到「整段模式能处理得更好」
     * 的情况就交给它（复用那套 401/409/503/504 状态机）。
     */
    private suspend fun streamOnce(
        text: String,
        timeoutSec: Int,
        voiceTurn: Boolean,
        onDelta: suspend (String) -> Unit,
    ): PiResult {
        val started = SystemClock.elapsedRealtime()
        val response = try {
            factory.streamApi().chatStream(
                ChatRequest(
                    text = text,
                    timeout = timeoutSec,
                    hint = if (voiceTurn) HINT_VOICE else null,
                )
            )
        } catch (e: IOException) {
            val ms = SystemClock.elapsedRealtime() - started
            record(null, null, "Unavailable", ms, "流式请求发不出去：网络异常", e.message)
            return PiResult.Unavailable(retryable = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val ms = SystemClock.elapsedRealtime() - started
            record(null, "client_error", "Failed", ms, "客户端异常", e.message)
            return PiResult.Failed(0, "client_error", e.message ?: e::class.java.simpleName)
        }

        /*
         * 非 200 一律退回整段模式重来一次，两种情况都落这一支：
         *   · pi 太老，根本没有 /v1/chat/stream → 404
         *   · 401 / 409 / 503 / 504 —— 整段那条路的状态机已经写全了
         *     （409/503 各自重试一次、504 先查 busy 再决定），复用它比自己再抄一遍可靠。
         */
        if (!response.isSuccessful) {
            val ms = SystemClock.elapsedRealtime() - started
            record(
                response.code(), null, "Fallback", ms,
                "流式端点不可用（HTTP ${response.code()}），退回整段模式", null,
            )
            return attempt(text, timeoutSec, retriesLeft = 1)
        }

        val body = response.body()
        if (body == null) {
            val ms = SystemClock.elapsedRealtime() - started
            record(response.code(), null, "Fallback", ms, "流式响应没有响应体，退回整段模式", null)
            return attempt(text, timeoutSec, retriesLeft = 1)
        }

        var reply = ""
        var tools: Int? = null
        var serverMs: Long? = null
        var deltaChars = 0
        var failure: PiEvent.Failure? = null
        var end = StreamEnd.Truncated
        var broken: String? = null

        try {
            end = body.source().use { source ->
                readPiEvents(source, PiClientFactory.json) { event ->
                    when (event) {
                        is PiEvent.Delta -> {
                            deltaChars += event.text.length
                            onDelta(event.text)
                        }

                        is PiEvent.Done -> {
                            reply = event.reply
                            tools = event.tools
                            serverMs = event.ms
                        }

                        is PiEvent.Failure -> failure = event
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 读了一半断了（读超时 / 网络掉线）：已经吐出来的部分照常算数，
            // 由下面的分支决定是重试还是如实报错
            broken = e.message ?: e::class.java.simpleName
        }

        val ms = SystemClock.elapsedRealtime() - started

        failure?.let { f ->
            // 「pi 说没起过」+「我们一个字都没收到」双重确认才敢重来：
            // 已经念出去半句再重试，用户会把开头听两遍。
            if (!f.started && deltaChars == 0) {
                record(
                    f.httpStatus, f.code, "Fallback", ms,
                    "流式 error(started=false) 且无 delta，退回整段重试一次", f.message,
                )
                return attempt(text, timeoutSec, retriesLeft = 0)
            }
            record(f.httpStatus, f.code, "Failed", ms, "流式 error(started=true)，不重试", f.message)
            return PiResult.Failed(f.httpStatus ?: 0, f.code, f.message ?: "流式回复中断")
        }

        if (end == StreamEnd.Done) {
            if (reply.isBlank()) {
                record(200, null, "Failed", ms, "流式 done 的 reply 是空的", null)
                return PiResult.Failed(200, null, "空回复（done 里没有内容）")
            }
            record(
                200, null, "Ok(stream)", ms,
                "delta ${deltaChars} 字 · tools=$tools · serverMs=$serverMs", null,
            )
            return PiResult.Ok(reply, tools, serverMs)
        }

        val note = broken ?: "连接在 [DONE] 之前就结束了"
        if (deltaChars == 0) {
            // 一个字都没收到，当没跑过：让上层可以整轮重来
            record(null, null, "Unavailable", ms, "流式连接中断且没收到内容：$note", null)
            return PiResult.Unavailable(retryable = true)
        }
        record(null, "stream_truncated", "Failed", ms, "流式回复不完整：$note", null)
        return PiResult.Failed(0, "stream_truncated", "回复没说完就断了")
    }

    /** `GET /healthz`，免鉴权探活。 */
    suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        try {
            val r = factory.api().health()
            val text = r.body()?.string()?.trim().orEmpty()
            val ms = SystemClock.elapsedRealtime() - started
            if (r.isSuccessful) {
                ProbeResult.Ok(ms, text.ifEmpty { "(空响应体)" })
            } else {
                ProbeResult.Fail("HTTP ${r.code()}", ms)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ProbeResult.Fail(e.message ?: e::class.java.simpleName, SystemClock.elapsedRealtime() - started)
        }
    }

    /** 结构化 status；失败返回 null。 */
    suspend fun statusOrNull(): PiStatus? = withContext(Dispatchers.IO) {
        try {
            val body = factory.api().status().body()
            // 本来就在拉 /v1/status，顺手把流式能力位的缓存刷新掉 ——
            // 省掉每轮语音对话前专门探一次能力的那次请求
            body?.let {
                streamCapable = it.stream == true
                streamProbedAt = SystemClock.elapsedRealtime()
            }
            body
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** Debug 页用的原始 JSON（美化过的）。 */
    suspend fun statusRawJson(): String = withContext(Dispatchers.IO) {
        try {
            val r = factory.api().statusRaw()
            val raw = r.body()?.string()
            if (r.isSuccessful) pretty(raw) else "HTTP ${r.code()}\n${raw.orEmpty()}"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "连接失败：${e.message ?: e::class.java.simpleName}"
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private fun record(
        httpCode: Int?,
        code: String?,
        label: String,
        ms: Long,
        note: String?,
        errorBody: String?,
    ) {
        _lastCall.value = CallRecord(
            at = System.currentTimeMillis(),
            httpCode = httpCode,
            code = code,
            resultLabel = label,
            ms = ms,
            note = note,
            errorBody = errorBody?.take(2000),
        )
    }

    /** Response.errorBody() 只能读一次，单独包一层防炸。 */
    private fun readErrorBody(response: retrofit2.Response<*>): String? =
        runCatching { response.errorBody()?.string() }.getOrNull()

    private fun parseError(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        return runCatching {
            val obj = PiClientFactory.json.parseToJsonElement(raw).jsonObject
            obj["code"]?.jsonPrimitive?.contentOrNull to obj["error"]?.jsonPrimitive?.contentOrNull
        }.getOrElse { null to raw.take(500) }
    }

    private fun pretty(raw: String?): String {
        if (raw.isNullOrBlank()) return "(空响应体)"
        return runCatching {
            val element: JsonElement = PiClientFactory.json.parseToJsonElement(raw)
            PRETTY.encodeToString(JsonElement.serializer(), element)
        }.getOrDefault(raw)
    }

    private companion object {
        val PRETTY = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** 语音来源的回合带上它，pi 会按口语稿回（短、无代码块/表格/裸链接）。 */
        const val HINT_VOICE = "voice"

        /** 流式能力位的缓存时长。能力位是 pi 版本的属性，10 分钟足够保守。 */
        const val STREAM_CAP_TTL_MS = 10 * 60 * 1000L
    }
}
