package com.pi.assistant.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.pi.assistant.data.prefs.PiSettings
import com.pi.assistant.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端侧关键词唤醒（sherpa-onnx KWS / zipformer）。
 *
 * 选它的理由很实际：中文开箱即用、换唤醒词只改一个文本文件、零训练成本，
 * 而且跟 VAD 共用同一个 native 库，不多一份 so。
 *
 * 模型生命周期（后台占用优化 A1）：
 *   KeywordSpotter 不持有麦克风，加载一次要读三个 onnx + 建会话，是几百 ms 的
 *   CPU 尖峰 —— 所以把它从「一次 listen」提升到「服务的生命周期」，只在配置
 *   （阈值 / 线程数）变化时重建。唤醒词不参与重建：它走 createStream(keywords)
 *   的按流词表（见 [WakeKeywords]），改词即时生效，不用动模型。
 *   AudioRecord 仍然每轮释放（要跟 VadRecorder 抢设备），两者在这里解耦。
 */
@Singleton
class KwsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {
    private val lock = Any()

    /** 服务存活期间复用同一个 spotter —— 模型只加载一次。 */
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var spotterKey: String? = null

    /** null 表示可用；否则是给用户看的原因。 */
    fun unavailableReason(): String? {
        if (!SherpaNative.available) return SherpaNative.reason
        val missing = VoiceAssets.missingForKws(context)
        return if (missing.isEmpty()) null
        else "assets 里缺：${missing.joinToString("、")}（跑 tools 里的取模型脚本）"
    }

    /**
     * 取一个可用的 spotter。只有配置变了才重建（阈值 / 线程数）。
     *
     * 快路径无锁读：key 一致直接复用。主循环每轮都会进来一次，
     * 别为这件事去抢锁。
     */
    private fun obtain(): KeywordSpotter? {
        val snapshot = settings.current
        val key = configKey(snapshot)

        spotter?.takeIf { spotterKey == key }?.let { return it }
        return synchronized(lock) {
            spotter?.takeIf { spotterKey == key } ?: run {
                releaseLocked()                                   // 配置变了，先拆旧的
                build(snapshot)?.also { spotter = it; spotterKey = key }
            }
        }
    }

    private fun configKey(snapshot: PiSettings): String = listOf(
        snapshot.kwsScore,
        snapshot.kwsThreshold,
        snapshot.kwsThreads,
    ).joinToString("|")

    /** 释放模型。由服务的 onDestroy 调用，平时不要动。 */
    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        runCatching { spotter?.release() }
        spotter = null
        spotterKey = null
    }

    private fun build(snapshot: PiSettings): KeywordSpotter? {
        val paths = VoiceAssets.resolveKws(context) ?: return null
        // NNAPI 已移除：流式小模型动态 shape 在 NNAPI EP 上经常编译失败，
        // 收益又不明显（8 Gen 3 单线程 CPU 已经很轻），固定 cpu。
        return runCatching {
            KeywordSpotter(
                assetManager = context.assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(
                        sampleRate = AudioRecorder.SAMPLE_RATE,
                        featureDim = 80,
                    ),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder,
                            decoder = paths.decoder,
                            joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        numThreads = snapshot.kwsThreads,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                    maxActivePaths = 4,
                    // 打包词表只用于满足初始化要求；真正生效的唤醒词
                    // 由 listen() 里 createStream(keywords) 按流传入
                    keywordsFile = paths.keywords,
                    keywordsScore = snapshot.kwsScore,
                    keywordsThreshold = snapshot.kwsThreshold,
                    numTrailingBlanks = 2,
                ),
            )
        }.onFailure {
            Log.w(TAG, "KeywordSpotter 初始化失败", it)
        }.getOrNull()
    }

    /**
     * 阻塞式监听循环，直到 [shouldContinue] 返回 false 或协程被取消。
     *
     * 注意这个函数**会一直占着麦克风**，调用方负责生命周期；
     * 播放回复时必须先把它停掉，否则会把自己播的声音当唤醒词（自激）。
     */
    suspend fun listen(
        shouldContinue: () -> Boolean,
        onKeyword: (String) -> Unit,
        onError: (String) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        unavailableReason()?.let {
            onError(it)
            return@withContext
        }

        val spotter = obtain()
        if (spotter == null) {
            onError("唤醒引擎初始化失败：模型或 assets 不可用")
            return@withContext
        }

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            // 注意：麦克风打不开不等于模型坏了，spotter 留着
            onError("麦克风打不开（权限被拒或被别的 App 占用）")
            return@withContext
        }

        // 按流词表：设置里的唤醒词（可多个，逗号隔开）即时生效；
        // null = 转不出拼音，建流时传空串回退打包词表
        val kwText = WakeKeywords.forSettings(context, settings.current.wakeKeyword)

        var stream: OnlineStream? = newStream(spotter, kwText)
        val buffer = ShortArray(AudioRecorder.CHUNK)
        val floats = FloatArray(AudioRecorder.CHUNK)

        try {
            while (shouldContinue() && isActive) {
                val read = recorder.read(buffer)
                if (read <= 0) continue

                for (i in 0 until read) floats[i] = buffer[i] / 32768f
                val chunk = if (read == floats.size) floats else floats.copyOf(read)

                val current = stream ?: break
                current.acceptWaveform(chunk, AudioRecorder.SAMPLE_RATE)

                while (spotter.isReady(current)) {
                    spotter.decode(current)
                    val keyword = spotter.getResult(current).keyword
                    if (keyword.isNotEmpty()) {
                        onKeyword(keyword)
                        // 命中后换一条干净的流：否则残留状态可能反复触发同一个词
                        runCatching { current.release() }
                        stream = newStream(spotter, kwText)
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "KWS 循环异常", t)
            onError("唤醒监听中断：${t.message ?: t::class.java.simpleName}")
        } finally {
            recorder.close()                    // 麦克风必须释放
            runCatching { stream?.release() }   // 流每轮换新的
            // spotter 不 release —— 归服务生命周期管
        }
    }

    /**
     * 建流。带按流词表时优先把设置里的唤醒词传给 native —— 老版本 .so 没有这个
     * JNI 入口时会抛 NoSuchMethodError，这里兜住并退回打包词表，只回退一次。
     */
    @Volatile private var perStreamKeywordsBroken = false

    private fun newStream(spotter: KeywordSpotter, kwText: String?): OnlineStream {
        if (!kwText.isNullOrEmpty() && !perStreamKeywordsBroken) {
            try {
                return spotter.createStream(kwText)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                perStreamKeywordsBroken = true
                Log.w(TAG, "createStream(keywords) 不受当前 .so 支持，回退打包词表", t)
            }
        }
        return spotter.createStream()
    }

    private companion object {
        const val TAG = "KwsEngine"
    }
}
