package com.archcomplex.aplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private var tracks: List<Track> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) loadLibrary() else showEmpty("Разрешение на музыку не предоставлено") }

    private val m3uLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        executor.execute {
            val stations = contentResolver.openInputStream(uri)?.bufferedReader()?.use { M3uParser.parse(it.readText()) }
                .orEmpty()
            runOnUiThread { playStations(stations) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = TrackAdapter { track -> playTrack(track) }
        findViewById<RecyclerView>(R.id.track_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<Button>(R.id.scan_button).setOnClickListener { requestAudioPermission() }
        findViewById<Button>(R.id.play_button).setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        findViewById<Button>(R.id.previous_button).setOnClickListener { controller?.seekToPreviousMediaItem() }
        findViewById<Button>(R.id.next_button).setOnClickListener { controller?.seekToNextMediaItem() }
        findViewById<Button>(R.id.m3u_button).setOnClickListener {
            m3uLauncher.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain"))
        }
        findViewById<Button>(R.id.radio_button).setOnClickListener { showRadioUrlDialog() }
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
            val result = mutableListOf<Track>()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.MIME_TYPE
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (${MediaStore.Audio.Media.MIME_TYPE} = ? OR ${MediaStore.Audio.Media.MIME_TYPE} = ?)",
                arrayOf("audio/mpeg", "audio/mp4"),
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) result += Track(
                    cursor.getLong(id), cursor.getString(title) ?: "Без названия",
                    cursor.getString(artist) ?: "Неизвестный исполнитель",
                    cursor.getString(album) ?: "Неизвестный альбом"
                )
            }
            tracks = result
            runOnUiThread { adapter.submitList(result); showEmpty(if (result.isEmpty()) "Музыка не найдена" else "") }
        }
    }

    private fun playTrack(track: Track) {
        val items = tracks.map { it.toMediaItem() }
        val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        controller?.setMediaItems(items, index, 0L)
        controller?.prepare()
        controller?.play()
        updateNowPlaying(track.title, track.artist)
    }

    private fun showRadioUrlDialog() {
        val input = EditText(this).apply { hint = "https://example.com/radio.mp3" }
        AlertDialog.Builder(this).setTitle("Добавить онлайн-радио").setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Воспроизвести") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    playStations(listOf(RadioStation("Онлайн-радио", url)))
                } else showEmpty("Введите корректный HTTP(S)-адрес")
            }.show()
    }

    private fun playStations(stations: List<RadioStation>) {
        if (stations.isEmpty()) { showEmpty("В M3U не найдено аудиопотоков"); return }
        val items = stations.map { station ->
            MediaItem.Builder().setUri(station.url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).build()).build()
        }
        controller?.setMediaItems(items)
        controller?.prepare()
        controller?.play()
        updateNowPlaying(stations.first().name, "Онлайн-радио")
        showEmpty("Загружено радиостанций: ${stations.size}")
    }

    private fun updateNowPlaying(title: String, artist: String) {
        findViewById<TextView>(R.id.now_playing).text = "▶ $title — $artist"
    }

    private fun showEmpty(message: String) { findViewById<TextView>(R.id.empty_label).text = message }

    override fun onDestroy() {
        if (::controllerFuture.isInitialized) MediaController.releaseFuture(controllerFuture)
        executor.shutdown()
        super.onDestroy()
    }
}

private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build())
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build())
    .build()
