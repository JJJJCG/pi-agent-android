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

    /**
     * pi bridge · 流式对话（`/v1/chat/stream`）。
     *
     * 和 [forPi] 的差别在超时：**不设 callTimeout**。
     * 一轮回答可能好几分钟（还要排在前一轮后面等），任何固定的总超时都会在
     * 用户正听着的时候把连接掐掉。卡死由 readTimeout 兜底 ——
     * pi 在静默期（排队 / 等首 token / 跑工具）每 10 秒发一行 `: ping`，
     * 所以 45 秒没有任何字节是明确异常。
     *
     * `Accept` 用 `header()` 覆盖而不是 `addHeader()`：根 client 不收 Accept，
     * 但显式声明 SSE 能让中间的反代知道别攒批。
     */
    fun forPiStream(): OkHttpClient = root.newBuilder()
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        // 流已经消费了一半再重试只会把前半句念两遍，交给上层判断
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val token = settings.current.token
            val b = chain.request().newBuilder().header("Accept", "text/event-stream")
            if (token.isNotBlank()) b.addHeader("Authorization", "Bearer $token")
            chain.proceed(b.build())
        }
        .build()

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

    /**
     * MiMo 语音 · 流式合成。
     *
     * 和 [forSpeech] 只差一处，但很关键：**不设 callTimeout**。
     * 流式是「边生成边收」，一段四千字的文本读下来可能好几分钟 —— 任何固定的总超时
     * 都会在播放中途把连接掐掉，而那时用户正听着。
     * 卡死交给 readTimeout 兜底：正常流式每几百毫秒就有一块音频，30 秒没动静
     * 是明确异常，报错比让用户盯着「正在朗读」强。
     *
     * 关掉隐式重试：流已经消费了一半再重试，只会把前半句念两遍。
     *
     * 日志拦截器（根 client 上的 BASIC）在这里是安全的 —— 它只记请求行和响应状态，
     * 不碰响应体。换成 BODY 会把整段音频先读进内存，流式就废了，别改。
     */
    fun forSpeechStream(token: String): OkHttpClient = root.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
            if (token.isNotBlank()) {
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
