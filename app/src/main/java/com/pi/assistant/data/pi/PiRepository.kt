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
            factory.api().status().body()
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
    }
}
