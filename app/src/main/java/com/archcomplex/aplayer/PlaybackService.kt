package com.archcomplex.aplayer

import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object { @Volatile var instance: PlaybackService? = null }
    private var player: ExoPlayer? = null; private var mediaSession: MediaSession? = null; private var equalizer: Equalizer? = null
    private val handler = Handler(Looper.getMainLooper()); private var sleepRunnable: Runnable? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    override fun onCreate() { super.onCreate(); instance = this
        player = ExoPlayer.Builder(this).build().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true); setHandleAudioBecomingNoisy(true) }
        try { equalizer = Equalizer(0, player!!.audioSessionId).apply { enabled = true } } catch (_: Exception) { equalizer = null }
        mediaSession = MediaSession.Builder(this, player!!).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    fun setVolumePercent(percent: Int) { player?.volume = percent.coerceIn(0, 100) / 100f }
    fun getVolumePercent() = ((player?.volume ?: 1f) * 100).toInt().coerceIn(0, 100)
    fun setStereoBalance(balance: Int) { val value = balance.coerceIn(-100, 100); try { audioManager.setParameters("audio_balance=${value / 100f}") } catch (_: Exception) {} }
    fun setBandLevel(index: Int, level: Int) { equalizer?.let { try { it.setBandLevel(index.toShort(), level.coerceIn(it.bandLevelRange[0].toInt(), it.bandLevelRange[1].toInt()).toShort()) } catch (_: Exception) {} } }
    fun setSleepTimer(minutes: Long) { cancelSleepTimer(); sleepRunnable = Runnable { player?.pause() }; handler.postDelayed(sleepRunnable!!, minutes * 60_000L) }
    fun cancelSleepTimer() { sleepRunnable?.let(handler::removeCallbacks); sleepRunnable = null }
    override fun onDestroy() { cancelSleepTimer(); equalizer?.release(); mediaSession?.release(); player?.release(); instance = null; super.onDestroy() }
}
