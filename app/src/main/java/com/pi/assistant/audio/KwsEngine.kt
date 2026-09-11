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
 * 唤醒词固定为打包词表 assets/kws/keywords.txt 里的「喵喵」
 * （[PiSettings.WAKE_WORD] 只用于界面提示）。曾经的「设置里改词、运行时
 * 转拼音换按流词表」方案在实际设备上不生效，已整体移除 —— 要换词就改
 * keywords.txt 重新打包，词表格式见该文件头注释。
 *
 * 模型生命周期（后台占用优化 A1）：
 *   KeywordSpotter 不持有麦克风，加载一次要读三个 onnx + 建会话，是几百 ms 的
 *   CPU 尖峰 —— 所以把它从「一次 listen」提升到「服务的生命周期」，只在配置
 *   （阈值 / 线程数）变化时重建。
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

    /**
     * 监听循环是否还在跑。
     *
     * 这是 [release] 与 `listen()` 之间唯一的握手信号，存在的理由很致命：
     * `spotter.release()` 是 native 调用，一旦它在 `isReady()` / `decode()`
     * 还在同一个 native 句柄上跑的时候被调掉，就是 use-after-free → SIGSEGV。
     * 这种崩溃 `runCatching` 接不住（连 catch(Throwable) 都不行），进程直接没，
     * 用户看到的就是「关监听闪退」。
     *
     * 所以 release 之前必须拿到这个锁，确保循环已经整个退出（见 listen 的
     * finally：退出时把 running 置回 false 并通知）。
     */
    private val loopLock = Object()
    private var loopRunning = false

    /**
     * 释放模型。由服务的 onDestroy 调用，平时不要动。
     *
     * **会阻塞**到监听循环彻底退出为止 —— 调用方绝不能在主线程上调它，
     * 否则就是一次「关开关 → 主线程卡住 → ANR」的重演。
     */
    fun release() {
        awaitLoopExit()
        synchronized(lock) { releaseLocked() }
    }

    /**
     * 等监听循环退出。最多等 [LOOP_EXIT_TIMEOUT_MS]，超时也放行 ——
     * 万一循环卡在 native read 里出不来，宁可冒险释放，也不能把服务
     * 停止流程永久挂死。
     */
    private fun awaitLoopExit() {
        synchronized(loopLock) {
            if (!loopRunning) return
            runCatching { loopLock.wait(LOOP_EXIT_TIMEOUT_MS) }
        }
    }

    /** 循环退出时由 listen 的 finally 调用，唤醒可能正在等待的 [release]。 */
    private fun signalLoopExit() {
        synchronized(loopLock) {
            loopRunning = false
            loopLock.notifyAll()
        }
    }

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
                    // 生效词表就是这份打包的 keywords.txt（唤醒词「喵喵」）
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

        // 从这一刻起到 finally 里的 signalLoopExit()，spotter 都在被本循环使用。
        // 打上标记后，release() 就会一直等到这里收尾干净才动手释放 native 句柄。
        synchronized(loopLock) { loopRunning = true }

        try {
            listenLoop(shouldContinue, onKeyword, onError)
        } finally {
            signalLoopExit()
        }
    }

    private suspend fun listenLoop(
        shouldContinue: () -> Boolean,
        onKeyword: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val spotter = obtain()
        if (spotter == null) {
            onError("唤醒引擎初始化失败：模型或 assets 不可用")
            return
        }

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            // 注意：麦克风打不开不等于模型坏了，spotter 留着
            onError("麦克风打不开（权限被拒或被别的 App 占用）")
            return
        }

        var stream: OnlineStream? = newStream(spotter)
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
                        stream = newStream(spotter)
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

    private fun newStream(spotter: KeywordSpotter): OnlineStream = spotter.createStream()

    private companion object {
        const val TAG = "KwsEngine"

        /**
         * release() 等监听循环退出的上限。
         *
         * 循环每 32ms 看一眼 shouldContinue，正常退出是毫秒级；留 2 秒是给
         * 「刚好卡在 native read 里」这种极端情况兜底。超时仍会释放 ——
         * 赌一次 use-after-free，也好过服务停不掉、麦克风一直亮着。
         */
        const val LOOP_EXIT_TIMEOUT_MS = 2_000L
    }
}
