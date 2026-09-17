package com.archcomplex.aplayer

import android.media.AudioManager
import android.media.audiofx.Equalizer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object {
        @Volatile
        var instance: PlaybackService? = null
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }
        try {
            equalizer = Equalizer(0, player!!.audioSessionId)
            equalizer?.enabled = true
        } catch (_: Exception) {
            equalizer = null
        }
        mediaSession = MediaSession.Builder(this, player!!).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    fun setVolumePercent(percent: Int) {
        val value = percent.coerceIn(0, 100) / 100f
        player?.volume = value
    }

    fun getVolumePercent(): Int = ((player?.volume ?: 1f) * 100).toInt().coerceIn(0, 100)

    fun setStereoBalance(balance: Int) {
        val normalized = balance.coerceIn(-100, 100)
        val left = ((100 - normalized) / 100f).coerceIn(0f, 1f)
        val right = ((100 + normalized) / 100f).coerceIn(0f, 1f)
        try {
            audioManager.setParameters("sound_balance=${normalized / 100f}")
        } catch (_: Exception) {
        }
        try {
            audioManager.setParameters("audio_balance=${normalized / 100f}")
        } catch (_: Exception) {
        }
        val global = ((left + right) / 2f).coerceIn(0f, 1f)
        player?.volume = (player?.volume ?: 1f) * global
    }

    fun setBandLevel(index: Int, level: Int) {
        equalizer ?: return
        val range = equalizer!!.bandLevelRange
        val min = range[0].toInt()
        val max = range[1].toInt()
        val clamped = level.coerceIn(min, max)
        try {
            equalizer!!.setBandLevel(index.toShort(), clamped.toShort())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        equalizer?.release()
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        equalizer = null
        instance = null
        super.onDestroy()
    }
}
