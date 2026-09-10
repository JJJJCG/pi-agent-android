package com.pi.assistant.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.pi.assistant.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「说完自动停」的录音器。
 *
 * 流程：AudioRecord 循环 → 归一化成 float 喂给 Silero VAD →
 *   说话开始后才落盘（省掉前面那段环境噪声）→ 静音超过阈值就收工。
 *
 * 两个兜底：最长录 [maxRecordSeconds]（防止用户忘了停），
 * 以及一直没人说话 5 秒就放弃（否则麦克风会一直占着）。
 */
@Singleton
class VadRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * @param onSpeechChanged 说话开始/结束的回调，用来驱动 UI 波形与提示
     * @return 录到的 WAV；没听到有效人声时返回 null
     */
    suspend fun record(onSpeechChanged: (Boolean) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        unavailableReason()?.let {
            Log.w(TAG, "VAD 不可用：$it")
            return@withContext null
        }

        val snapshot = settings.current

        val vad = try {
            Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VoiceAssets.VAD_MODEL,
                        threshold = snapshot.vadThreshold,
                        // 静音时长我们自己在外面按毫秒数，这里给个小值让它尽快翻状态
                        minSilenceDuration = 0.1f,
                        minSpeechDuration = 0.25f,
                        windowSize = WINDOW,
                    ),
                    sampleRate = AudioRecorder.SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VAD 初始化失败", t)
            return@withContext null
        }

        val recorder = AudioRecorder()
        if (!recorder.start()) {
            Log.w(TAG, "麦克风打不开（权限或占用）")
            runCatching { vad.release() }
            return@withContext null
        }

        val target = File(context.cacheDir, "utt_${System.currentTimeMillis()}.wav")
        val writer = WavWriter(target)
        val buffer = ShortArray(WINDOW)
        val floats = FloatArray(WINDOW)

        var speaking = false
        var speechMs = 0
        var silenceMs = 0
        var totalMs = 0

        try {
            while (true) {
                val read = recorder.read(buffer)
                if (read <= 0) break

                for (i in 0 until read) floats[i] = buffer[i] / 32768f
                vad.acceptWaveform(if (read == floats.size) floats else floats.copyOf(read))

                val frameMs = read * 1000 / AudioRecorder.SAMPLE_RATE
                totalMs += frameMs

                if (vad.isSpeechDetected()) {
                    speechMs += frameMs
                    silenceMs = 0
                    if (!speaking) {
                        speaking = true
                        onSpeechChanged(true)
                    }
                } else if (speaking) {
                    silenceMs += frameMs
                }

                if (speaking) writer.write(buffer, read)

                val finished = (speaking && silenceMs >= snapshot.vadMinSilenceMs) ||
                    totalMs >= snapshot.maxRecordSeconds * 1000 ||
                    (!speaking && totalMs >= NO_SPEECH_TIMEOUT_MS)
                if (finished) break
            }
            runCatching { vad.flush() }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "录音循环中断", t)
        } finally {
            recorder.close()
            writer.close()
            runCatching { vad.release() }
            onSpeechChanged(false)
        }

        val tooShort = !speaking || speechMs < MIN_SPEECH_MS || writer.bytesWritten <= 0L
        if (tooShort) {
            target.delete()
            null
        } else {
            Log.d(TAG, "录到 ${writer.durationMs}ms 有效语音")
            target
        }
    }

    /** null 表示可用；否则是给用户看的原因。 */
    fun unavailableReason(): String? = when {
        !SherpaNative.available -> SherpaNative.reason
        !VoiceAssets.vadReady(context) -> "assets 里缺 ${VoiceAssets.VAD_MODEL}（跑 tools 里的取模型脚本）"
        else -> null
    }

    private companion object {
        const val TAG = "VadRecorder"
        const val WINDOW = AudioRecorder.CHUNK
        const val MIN_SPEECH_MS = 250
        const val NO_SPEECH_TIMEOUT_MS = 5_000
    }
}
