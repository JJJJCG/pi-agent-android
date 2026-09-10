package com.pi.assistant.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * 边录边写 WAV（44 字节头 + 裸 PCM）。
 *
 * 16k/mono/16bit 的 WAV 是兼容性最好的交换格式：几乎每个 OpenAI 兼容端点都认，
 * 且 sherpa-onnx 的离线识别也吃它。头里的两个长度字段要等录完才知道，
 * 所以先占位、close 时回填（RandomAccessFile 定位改写）。
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int = AudioRecorder.SAMPLE_RATE,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    val bytesWritten: Long get() = dataBytes

    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSecond == 0L) 0L else dataBytes * 1000L / bytesPerSecond
        }

    init {
        raf.setLength(0L)
        writePlaceholderHeader()
    }

    fun write(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            raf.seek(0L)
            writeHeader(dataBytes)
            raf.seek(raf.length())
        }
        runCatching { raf.close() }
    }

    // ------------------------------------------------------------------ 内部

    private fun writePlaceholderHeader() = writeHeader(0L)

    private fun writeHeader(data: Long) {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        writeAscii("RIFF")
        writeIntLe((36 + data).toInt())
        writeAscii("WAVE")

        writeAscii("fmt ")
        writeIntLe(16)                    // PCM 子块长度
        writeShortLe(1)                   // audioFormat = PCM
        writeShortLe(channels)
        writeIntLe(sampleRate)
        writeIntLe(byteRate)
        writeShortLe(blockAlign)
        writeShortLe(bitsPerSample)

        writeAscii("data")
        writeIntLe(data.toInt())
    }

    private fun writeAscii(text: String) {
        raf.write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun writeIntLe(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLe(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }
}
