package com.pi.assistant.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
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
 */
@Singleton
class KwsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    /** null 表示可用；否则是给用户看的原因。 */
    fun unavailableReason(): String? {
        if (!SherpaNative.available) return SherpaNative.reason
        val missing = VoiceAssets.missingForKws(context)
        return if (missing.isEmpty()) null
        else "assets 里缺：${missing.joinToString("、")}（跑 tools 里的取模型脚本）"
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
        val reason = unavailableReason()
        if (reason != null) {
            onError(reason)
            return@withContext
        }

        val snapshot = settings.current
        val paths = VoiceAssets.resolveKws(context)
        if (paths == null) {
            onError("找不到关键词模型：" + VoiceAssets.missingForKws(context).joinToString("、"))
            return@withContext
        }

        val spotter = try {
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
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                    maxActivePaths = 4,
                    keywordsFile = paths.keywords,
                    keywordsScore = snapshot.kwsScore,
                    keywordsThreshold = snapshot.kwsThreshold,
                    numTrailingBlanks = 2,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "KWS 初始化失败", t)
            onError("唤醒引擎初始化失败：${t.message ?: t::class.java.simpleName}")
            return@withContext
        }

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            runCatching { spotter.release() }
            onError("麦克风打不开（权限被拒或被别的 App 占用）")
            return@withContext
        }

        var stream: OnlineStream? = spotter.createStream()
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
                        stream = spotter.createStream()
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "KWS 循环异常", t)
            onError("唤醒监听中断：${t.message ?: t::class.java.simpleName}")
        } finally {
            recorder.close()
            runCatching { stream?.release() }
            runCatching { spotter.release() }
        }
    }

    private companion object {
        const val TAG = "KwsEngine"
    }
}
