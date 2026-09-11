package com.pi.assistant.data.net

import com.pi.assistant.data.prefs.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全 App 唯一的 OkHttp 根 client（后台占用优化 A7）。
 *
 * 之前 pi bridge 和语音各建一个 OkHttpClient，等于两组线程池 + 两个连接池。
 * OkHttp 官方的共享方式是 `newBuilder()` 派生 —— 派生出来的 client 共享同一个
 * dispatcher 和连接池，只覆盖超时和拦截器。
 */
@Singleton
class HttpClients @Inject constructor(
    private val settings: SettingsStore,
) {
    /** 唯一的根 client。超时与鉴权都在派生层加，它自己不带业务拦截器。 */
    private val root: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .build()

    /** pi bridge。客户端超时必须 > 服务端 timeout（+30s，见 README §6-2）。 */
    fun forPi(timeoutSec: Int): OkHttpClient {
        val t = (timeoutSec + 30).toLong()
        return root.newBuilder()
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(t, TimeUnit.SECONDS)
            .callTimeout(t, TimeUnit.SECONDS)
            // 重试语义由 PiRepository 按 409/503/504 分别决定，这里禁掉隐式重试
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                // 实时读 token：改 token 后无需重建 client
                val token = settings.current.token
                val b = chain.request().newBuilder().addHeader("Accept", "application/json")
                if (token.isNotBlank()) b.addHeader("Authorization", "Bearer $token")
                chain.proceed(b.build())
            }
            .build()
    }

    /** MiMo 语音。长音频识别和整段合成都慢，单独给足时间。 */
    fun forSpeech(token: String): OkHttpClient = root.newBuilder()
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
            if (token.isNotBlank()) {
                // MiMo 的两份官方示例鉴权头不一致：curl 写的是 `api-key`，
                // Python(OpenAI SDK) 走 Authorization。既然都可能，就两个都带上。
                b.addHeader("Authorization", "Bearer $token").addHeader("api-key", token)
            }
            chain.proceed(b.build())
        }
        .build()

    /** 进后台收工用：掐掉在途请求 + 关掉空闲连接（IdleReaper 调用）。 */
    fun release() {
        root.dispatcher.cancelAll()
        root.connectionPool.evictAll()
    }
}
