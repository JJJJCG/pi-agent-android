package com.pi.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * 音频焦点。不做这步，来电或别人放歌时会跟我们互相踩，更糟的是
 * 播放期间麦克风还在采，直接把 App 自己播的声音当唤醒词 —— 无限自激。
 */
class AudioFocusHelper(context: Context) {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private var focusRequest: AudioFocusRequest? = null

    /** 失去焦点：立刻停止播放并把麦克风让出去。 */
    var onFocusLost: (() -> Unit)? = null

    fun request(): Boolean {
        val manager = audioManager ?: return false
        if (focusRequest != null) return true

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    -> onFocusLost?.invoke()
                }
            }
            .build()

        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            focusRequest = request
        } else {
            Log.w(TAG, "未拿到音频焦点，播放可能被系统压制")
        }
        return granted
    }

    fun release() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        const val TAG = "AudioFocusHelper"
    }
}
