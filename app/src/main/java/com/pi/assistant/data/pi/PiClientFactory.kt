package com.pi.assistant.data.pi

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pi.assistant.data.net.HttpClients
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按当前设置动态构建 [PiBridgeApi]。
 *
 * 为什么不直接 @Provides 一个单例：
 *  - baseUrl 会变（换网络、pi 换机器），写死就得改包；
 *  - timeout 改了必须重建 client，因为 OkHttp 的超时是构造期固定的。
 *
 * 于是这里缓存「baseUrl + timeout」这一把钥匙对应的实例，变了就重建。
 * token 走拦截器实时读取，改 token 不用重建。
 *
 * OkHttpClient 从 [HttpClients] 的根 client 派生（A7）：共享 dispatcher 和
 * 连接池，只覆盖超时与鉴权拦截器 —— 不再自己另起一套线程池。
 */
@Singleton
class PiClientFactory @Inject constructor(
    private val settings: SettingsStore,
    private val clients: HttpClients,
) {
    private val json = Json {
        ignoreUnknownKeys = true   // pi 版本升级加字段时不能让 App 崩
        explicitNulls = false
        coerceInputValues = true
    }

    @Volatile private var cacheKey: String? = null
    @Volatile private var cached: PiBridgeApi? = null

    /** 流式实例单独一把缓存钥匙：它的超时策略和整段模式不同（不设总超时）。 */
    @Volatile private var streamCacheKey: String? = null
    @Volatile private var cachedStream: PiBridgeApi? = null

    fun api(): PiBridgeApi = apiFor(settings.current)

    /**
     * `/v1/chat/stream` 用的实例。
     *
     * 只按 baseUrl 缓存 —— 流式请求不设总超时，`timeoutSec` 是给服务端用的
     * （写在请求体里），不影响客户端，所以不必进钥匙。
     */
    fun streamApi(): PiBridgeApi {
        val baseUrl = settings.current.baseUrl.trimEnd('/')
        require(baseUrl.isNotBlank()) { "pi 地址为空" }
        cachedStream?.takeIf { streamCacheKey == baseUrl }?.let { return it }
        return synchronized(this) {
            cachedStream?.takeIf { streamCacheKey == baseUrl } ?: build(
                baseUrl = baseUrl,
                client = clients.forPiStream(),
            ).also {
                cachedStream = it
                streamCacheKey = baseUrl
            }
        }
    }

    /** 允许用一份覆盖设置去构建（设置页的「探活」按钮用未保存的值试）。 */
    fun apiFor(snapshot: PiSettings): PiBridgeApi {
        val baseUrl = snapshot.baseUrl.trimEnd('/')
        require(baseUrl.isNotBlank()) { "pi 地址为空" }
        val key = "$baseUrl|${snapshot.timeoutSec}"
        cached?.takeIf { cacheKey == key }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { cacheKey == key } ?: build(
                baseUrl = baseUrl,
                client = clients.forPi(snapshot.timeoutSec),
            ).also {
                cached = it
                cacheKey = key
            }
        }
    }

    private fun build(baseUrl: String, client: OkHttpClient): PiBridgeApi =
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PiBridgeApi::class.java)

    companion object {
        /** Debug 页展示原始 JSON 时用同一个 Json 配置。 */
        val json: Json = Json { ignoreUnknownKeys = true }
    }
}
