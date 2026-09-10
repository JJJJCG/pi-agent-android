package com.pi.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.Closeable

/**
 * 录音的最薄一层封装。
 *
 * 几个刻意的选择：
 *   · `VOICE_RECOGNITION` 而不是 `MIC` —— 它自带降噪和 AGC，对识别明显更友好
 *   · 16k / mono / PCM16 —— 所有兼容端点和 sherpa-onnx 都认这一套
 *   · buffer 取 minBufferSize 的 2 倍 —— 留出抖动余量，避免 read 频繁欠载
 */
class AudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) : Closeable {

    private var record: AudioRecord? = null

    val isRecording: Boolean
        get() = record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /** 失败时返回 false，调用方据此提示「麦克风被占用/没权限」。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.w(TAG, "getMinBufferSize 返回 $minBuffer，参数不被设备支持")
            return false
        }

        val candidates = intArrayOf(source, MediaRecorder.AudioSource.MIC)
        for (candidate in candidates) {
            val created = runCatching {
                AudioRecord(candidate, sampleRate, CHANNEL, ENCODING, minBuffer * 2)
            }.getOrNull() ?: continue

            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                continue
            }

            val startedOk = runCatching { created.startRecording() }
                .onFailure { created.release() }
                .isSuccess
            if (!startedOk) continue

            record = created
            return true
        }

        Log.w(TAG, "AudioRecord 初始化失败")
        return false
    }

    /** 返回读到的样本数；<=0 表示出错或已停止。 */
    fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    override fun close() {
        val current = record ?: return
        record = null
        runCatching { if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) current.stop() }
        runCatching { current.release() }
    }

    companion object {
        private const val TAG = "AudioRecorder"

        const val SAMPLE_RATE = 16_000

        /** VAD 的 windowSize 是 512（32ms），块长取它正好对齐。 */
        const val CHUNK = 512

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
