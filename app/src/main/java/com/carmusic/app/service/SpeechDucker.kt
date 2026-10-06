package com.carmusic.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * 其他应用播报语音（导航、语音助手、通话等）时压低音乐，结束后恢复。
 * 车机上导航与音乐走不同声道且可同时出声，系统不会给音乐应用发压低事件，所以自行监听系统的播放活动。
 */
internal class SpeechDucker(context: Context, private val setVolume: (Float) -> Unit) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var ducked = false
    private val restore = Runnable { ducked = false; setVolume(1f) }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val speaking = configs.any { it.audioAttributes.usage in SPEECH_USAGES }
            main.removeCallbacks(restore)
            if (speaking) {
                if (!ducked) { ducked = true; setVolume(DUCKED_VOLUME) }
            } else if (ducked) {
                main.postDelayed(restore, RESTORE_DELAY_MS)
            }
        }
    }

    fun start() = audio.registerAudioPlaybackCallback(callback, main)

    fun stop() {
        audio.unregisterAudioPlaybackCallback(callback)
        main.removeCallbacks(restore)
    }

    private companion object {
        const val DUCKED_VOLUME = 0.1f
        const val RESTORE_DELAY_MS = 800L
        val SPEECH_USAGES = setOf(
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )
    }
}
