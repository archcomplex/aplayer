package com.archcomplex.aplayer

import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    companion object { @Volatile var instance: PlaybackService? = null }
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private val handler=Handler(Looper.getMainLooper()); private var sleep:Runnable?=null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    override fun onCreate(){ super.onCreate(); instance=this; player=ExoPlayer.Builder(this).build().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true); setHandleAudioBecomingNoisy(true) }; try{equalizer=Equalizer(0,player.audioSessionId).apply{enabled=true}}catch(_:Exception){}; session=MediaSession.Builder(this,player).build() }
    override fun onGetSession(info:MediaSession.ControllerInfo)=session
    fun setVolumePercent(value:Int){player.volume=value.coerceIn(0,100)/100f}
    fun setPlaybackSpeed(speed:Float){player.setPlaybackParameters(PlaybackParameters(speed.coerceIn(.25f,3f)))}
    fun setStereoBalance(value:Int){try{audioManager.setParameters("audio_balance=${value.coerceIn(-100,100)/100f}")}catch(_:Exception){}}
    fun setBandLevel(index:Int,level:Int){equalizer?.let{try{if(index<it.numberOfBands)it.setBandLevel(index.toShort(),level.coerceIn(it.bandLevelRange[0].toInt(),it.bandLevelRange[1].toInt()).toShort())}catch(_:Exception){}}}
    fun setSleepTimer(minutes:Long){cancelSleepTimer();sleep=Runnable{player.pause()};handler.postDelayed(sleep!!,minutes*60000)}
    fun cancelSleepTimer(){sleep?.let(handler::removeCallbacks);sleep=null}
    override fun onDestroy(){cancelSleepTimer();equalizer?.release();session?.release();player.release();instance=null;super.onDestroy()}
}
