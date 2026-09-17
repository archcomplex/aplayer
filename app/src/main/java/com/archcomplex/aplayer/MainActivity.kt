package com.archcomplex.aplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private lateinit var adapter: TrackAdapter
    private val executor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) loadLibrary() else showEmpty("Разрешение на музыку не предоставлено") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = TrackAdapter { track -> play(track) }
        findViewById<RecyclerView>(R.id.track_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<Button>(R.id.scan_button).setOnClickListener { requestAudioPermission() }
        findViewById<Button>(R.id.play_button).setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        requestAudioPermission()
        connectToPlaybackService()
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({ controller = controllerFuture.get() }, ContextCompat.getMainExecutor(this))
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadLibrary()
        else permissionLauncher.launch(permission)
    }

    private fun loadLibrary() {
        executor.execute {
            val tracks = mutableListOf<Track>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM
            )
            contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) tracks += Track(
                    cursor.getLong(id), cursor.getString(title), cursor.getString(artist), cursor.getString(album)
                )
            }
            runOnUiThread { adapter.submitList(tracks); showEmpty(if (tracks.isEmpty()) "Музыка не найдена" else "") }
        }
    }

    private fun play(track: Track) {
        val item = MediaItem.Builder().setMediaId(track.id.toString())
            .setUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(track.id.toString()).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build())
            .build()
        controller?.setMediaItem(item)
        controller?.prepare()
        controller?.play()
        findViewById<TextView>(R.id.now_playing).text = "▶ ${track.title} — ${track.artist}"
    }

    private fun showEmpty(message: String) { findViewById<TextView>(R.id.empty_label).text = message }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        executor.shutdown()
        super.onDestroy()
    }
}
