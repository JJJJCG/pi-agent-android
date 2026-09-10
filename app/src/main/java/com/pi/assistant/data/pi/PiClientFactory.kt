package com.pi.assistant.data.pi

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
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
 */
@Singleton
class PiClientFactory @Inject constructor(
    private val settings: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true   // pi 版本升级加字段时不能让 App 崩
        explicitNulls = false
        coerceInputValues = true
    }

    @Volatile private var cacheKey: String? = null
    @Volatile private var cached: PiBridgeApi? = null

    fun api(): PiBridgeApi = apiFor(settings.current)

    /** 允许用一份覆盖设置去构建（设置页的「探活」按钮用未保存的值试）。 */
    fun apiFor(snapshot: PiSettings): PiBridgeApi {
        val baseUrl = snapshot.baseUrl.trimEnd('/')
        require(baseUrl.isNotBlank()) { "pi 地址为空" }
        val key = "$baseUrl|${snapshot.timeoutSec}"
        cached?.takeIf { cacheKey == key }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { cacheKey == key } ?: build(snapshot).also {
                cached = it
                cacheKey = key
            }
        }
    }

    private fun build(snapshot: PiSettings): PiBridgeApi {
        // 客户端超时必须 > 服务端 timeout，否则先被自己掐断，pi 却还在跑
        val clientTimeout = (snapshot.timeoutSec + 30).toLong()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(clientTimeout, TimeUnit.SECONDS)
            .callTimeout(clientTimeout, TimeUnit.SECONDS)
            // 重试语义由 PiRepository 按 409/503/504 分别决定，这里禁掉隐式重试
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                // 实时读 token：改 token 后无需重建 client
                val token = settings.current.token
                val builder = chain.request().newBuilder()
                    .addHeader("Accept", "application/json")
                if (token.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
            .build()

        return Retrofit.Builder()
            .baseUrl("${snapshot.baseUrl.trimEnd('/')}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PiBridgeApi::class.java)
    }

    companion object {
        /** Debug 页展示原始 JSON 时用同一个 Json 配置。 */
        val json: Json = Json { ignoreUnknownKeys = true }
    }
}
