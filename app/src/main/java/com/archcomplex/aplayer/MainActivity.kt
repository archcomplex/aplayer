package com.archcomplex.aplayer

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
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

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, IntentFlags.READ_WRITE, IntentFlags.READ)
        } catch (_: SecurityException) {
            contentResolver.takePersistableUriPermission(uri, IntentFlags.READ)
        }
        FolderLibrary.addFolder(this, uri)
        loadLibrary()
    }

    private val m3uLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        executor.execute {
            val stations = contentResolver.openInputStream(uri)?.bufferedReader()?.use { M3uParser.parse(it.readText()) }.orEmpty()
            runOnUiThread { playStations(stations) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupEqualizerControls()
        adapter = TrackAdapter { playTrack(it) }
        findViewById<RecyclerView>(R.id.track_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<Button>(R.id.add_folder_button).setOnClickListener { folderLauncher.launch(null) }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { loadLibrary() }
        findViewById<Button>(R.id.sleep_timer_button).setOnClickListener { showSleepTimerDialog() }
        findViewById<Button>(R.id.play_button).setOnClickListener { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
        findViewById<Button>(R.id.previous_button).setOnClickListener { controller?.seekToPreviousMediaItem() }
        findViewById<Button>(R.id.next_button).setOnClickListener { controller?.seekToNextMediaItem() }
        findViewById<Button>(R.id.m3u_button).setOnClickListener { m3uLauncher.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain")) }
        findViewById<Button>(R.id.radio_button).setOnClickListener { showRadioUrlDialog() }
        connectToPlaybackService()
        loadLibrary()
    }

    private fun loadLibrary() {
        executor.execute {
            val result = FolderLibrary.scan(this)
            tracks = result
            runOnUiThread {
                adapter.submitList(result)
                showEmpty(if (result.isEmpty()) "Добавьте папку с музыкой" else "Найдено треков: ${result.size}")
            }
        }
    }

    private fun playTrack(track: Track) {
        val items = tracks.map { it.toMediaItem() }
        val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        controller?.setMediaItems(items, index, 0L)
        controller?.prepare(); controller?.play()
        updateNowPlaying(track.title, track.artist)
    }

    private fun showSleepTimerDialog() {
        val input = EditText(this).apply { hint = "Минуты"; inputType = 2 }
        AlertDialog.Builder(this).setTitle("Таймер сна")
            .setMessage("Остановить воспроизведение через указанное число минут")
            .setView(input).setNegativeButton("Отмена", null)
            .setNeutralButton("Выключить таймер") { _, _ -> PlaybackService.instance?.cancelSleepTimer(); showEmpty("Таймер сна выключен") }
            .setPositiveButton("Запустить") { _, _ ->
                val minutes = input.text.toString().toLongOrNull()?.coerceIn(1, 24 * 60)
                if (minutes == null) showEmpty("Введите количество минут от 1 до 1440")
                else { PlaybackService.instance?.setSleepTimer(minutes); showEmpty("Таймер сна: $minutes мин") }
            }.show()
    }

    private fun showRadioUrlDialog() {
        val input = EditText(this).apply { hint = "https://example.com/radio.mp3" }
        AlertDialog.Builder(this).setTitle("Добавить онлайн-радио").setView(input)
            .setNegativeButton("Отмена", null).setPositiveButton("Воспроизвести") { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://")) playStations(listOf(RadioStation("Онлайн-радио", url)))
                else showEmpty("Введите корректный HTTP(S)-адрес")
            }.show()
    }

    private fun playStations(stations: List<RadioStation>) {
        if (stations.isEmpty()) { showEmpty("В M3U не найдено аудиопотоков"); return }
        controller?.setMediaItems(stations.map { MediaItem.Builder().setUri(it.url).setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build()).build() })
        controller?.prepare(); controller?.play(); updateNowPlaying(stations.first().name, "Онлайн-радио")
    }

    private fun setupEqualizerControls() {
        val volume = findViewById<SeekBar>(R.id.volume_seek_bar); val balance = findViewById<SeekBar>(R.id.balance_seek_bar)
        volume.setOnSeekBarChangeListener(simpleSeek { findViewById<TextView>(R.id.volume_value).text = "$it%"; PlaybackService.instance?.setVolumePercent(it) })
        balance.max = 200; balance.progress = 100
        balance.setOnSeekBarChangeListener(simpleSeek { val value = it - 100; findViewById<TextView>(R.id.balance_value).text = if (value == 0) "0%" else if (value < 0) "L ${-value}%" else "R $value%"; PlaybackService.instance?.setStereoBalance(value) })
        val bass = findViewById<SeekBar>(R.id.bass_seek_bar); val treble = findViewById<SeekBar>(R.id.treble_seek_bar)
        bass.setOnSeekBarChangeListener(simpleSeek { val value = (it - 500) / 10; findViewById<TextView>(R.id.bass_value).text = "$value dB"; PlaybackService.instance?.setBandLevel(0, value) })
        treble.setOnSeekBarChangeListener(simpleSeek { val value = (it - 500) / 10; findViewById<TextView>(R.id.treble_value).text = "$value dB"; PlaybackService.instance?.setBandLevel(1, value) })
    }

    private fun simpleSeek(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { action(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({ controller = controllerFuture.get() }, ContextCompat.getMainExecutor(this))
    }
    private fun updateNowPlaying(title: String, artist: String) { findViewById<TextView>(R.id.now_playing).text = "▶ $title — $artist" }
    private fun showEmpty(message: String) { findViewById<TextView>(R.id.empty_label).text = message }
    override fun onDestroy() { if (::controllerFuture.isInitialized) MediaController.releaseFuture(controllerFuture); executor.shutdown(); super.onDestroy() }
}

private object IntentFlags { const val READ = 1; const val READ_WRITE = 3 }
private fun Track.toMediaItem() = MediaItem.Builder().setMediaId(id).setUri(Uri.parse(uri)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
