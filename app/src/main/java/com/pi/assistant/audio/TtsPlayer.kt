package com.pi.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * TTS 音频播放。
 *
 * 用 MediaPlayer 而不是 AudioTrack：兼容端点返回的可能是 mp3 / opus / wav，
 * MediaPlayer 直接吃容器格式，省掉自己解码。
 * 播放是**挂起**的 —— 调用方必须等它播完才能恢复麦克风采集。
 */
@Singleton
class TtsPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var player: MediaPlayer? = null
    private var pending: CancellableContinuation<Boolean>? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** 播完返回 true；出错或被 stop 打断返回 false。 */
    suspend fun play(file: File): Boolean = withContext(Dispatchers.Main.immediate) {
        finish(false)
        suspendCancellableCoroutine<Boolean> { cont ->
            pending = cont
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer 出错 what=$what extra=$extra")
                    finish(false)
                    true
                }
                cont.invokeOnCancellation { finish(false) }
                mp.prepareAsync()
            } catch (t: Throwable) {
                Log.w(TAG, "播放失败：${file.name}", t)
                finish(false)
            }
        }
    }

    fun stop() = finish(false)

    private fun finish(result: Boolean) {
        releasePlayer()
        val cont = pending
        pending = null
        if (cont?.isActive == true) cont.resume(result)
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
    }

    private companion object {
        const val TAG = "TtsPlayer"
    }
}
