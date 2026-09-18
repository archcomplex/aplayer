package com.archcomplex.aplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("abplayer", MODE_PRIVATE) }
    private lateinit var pager: LinearLayout
    private lateinit var tracksView: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: SeekBar
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private var screen = 1
    private var selectedPlaylist = 0
    private var selectedTrack = -1
    private var shuffle = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var aPoint = C.TIME_UNSET
    private var bPoint = C.TIME_UNSET
    private var stopAfterTrack = false
    private var stopAfterPlaylist = false
    private var sleepRunnable: Runnable? = null
    private var downX = 0f
    private var swipeCandidate = false
    private var light = false
    private val pages = mutableListOf<View>()
    private val playlists = mutableListOf<Playlist>()

    private val progressUpdater = object : Runnable {
        override fun run() {
            controller?.let { updatePlayerUi(it) }
            handler.postDelayed(this, 500)
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { takeReadPermission(it); addUriIfMissing(it) }
        persist(); render()
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        takeReadPermission(uri); FolderLibrary.addFolder(this, uri)
        Thread {
            val found = FolderLibrary.scan(this)
            runOnUiThread {
                found.forEach { addTrackIfMissing(it) }
                persist(); render(); toast("Найдено файлов: ${found.size}")
            }
        }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        light = prefs.getBoolean("light", false)
        loadPlaylists()
        buildUi()
        connectController()
        handler.post(progressUpdater)
        pager.post { showScreen(1) }
        render()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                swipeCandidate = !isInteractiveTarget(event.rawX.toInt(), event.rawY.toInt())
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                if (swipeCandidate && abs(dx) > 90 && abs(dx) > abs(event.rawY - event.downTime)) {
                    showScreen(screen + if (dx < 0) 1 else -1)
                    swipeCandidate = false
                    return true
                }
                swipeCandidate = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInteractiveTarget(x: Int, y: Int): Boolean {
        val v = window.decorView.findViewById<View>(android.R.id.content)
        return findInteractive(v, x, y)
    }

    private fun findInteractive(view: View, x: Int, y: Int): Boolean {
        if (view.visibility != View.VISIBLE || x !in view.left..view.right || y !in view.top..view.bottom) return false
        if (view is Button || view is EditText || view is SeekBar || view is Switch || view.isClickable) return true
        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) if (findInteractive(view.getChildAt(i), x, y)) return true
        return false
    }

    private fun buildUi() {
        pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bgColor()) }
        pages += settingsPage(); pages += playerPage(); pages += filesPage()
        pages.forEach { pager.addView(it, LinearLayout.LayoutParams(-1, -1)) }
        setContentView(pager)
        window.statusBarColor = bgColor(); window.navigationBarColor = bgColor()
    }

    private fun settingsPage(): View {
        val root = scrollColumn(); header(root, "ABPlayer", "Настройки и плейлисты") { showMenu() }
        root.addView(section("ПОИСК ПЛЕЙЛИСТОВ")); val search = edit("Поиск плейлистов..."); root.addView(search)
        root.addView(section("ПЛЕЙЛИСТЫ")); val list = column(); root.addView(list)
        search.addTextChangedListener(SimpleTextWatcher { q -> drawPlaylists(list, q) }); drawPlaylists(list, "")
        root.addView(section("НАСТРОЙКИ ВОСПРОИЗВЕДЕНИЯ")); val settings = column(); root.addView(settings)
        listOf("Восстанавливать позицию", "Предзагрузка следующего трека", "Плавное начало и окончание", "Кроссфейд", "ReplayGain", "Нормализация громкости").forEachIndexed { i, name ->
            settings.addView(settingRow(name, prefs.getBoolean("setting_$i", i != 3)) { value -> prefs.edit().putBoolean("setting_$i", value).apply() })
        }
        root.addView(section("ИНТЕРФЕЙС")); val ui = column(); root.addView(ui)
        ui.addView(controlRow("Тема", button(if (light) "Light Theme" else "Dark Theme") { light = !light; prefs.edit().putBoolean("light", light).apply(); recreate() }))
        ui.addView(settingRow("Анимации", true) {}); ui.addView(settingRow("Автоповорот", true) {})
        root.addView(button("＋ Создать плейлист") { createPlaylist() })
        return root
    }

    private fun playerPage(): View {
        val root = scrollColumn(); header(root, "ABPlayer", "Избранное") { showMenu() }
        root.addView(TextView(this).apply { text = "♫"; textSize = 88f; gravity = Gravity.CENTER; setTextColor(Color.rgb(220, 235, 255)); setBackgroundColor(Color.rgb(28, 42, 61) }, LinearLayout.LayoutParams(-1, dp(230)))
        val card = cardColumn(); root.addView(card)
        statusView = label("ГОТОВ К ВОСПРОИЗВЕДЕНИЮ"); card.addView(statusView)
        titleView = text("Выберите трек", 25f); card.addView(titleView); artistView = muted("ABPlayer"); card.addView(artistView)
        val tools = row(); tools.addView(button("Sleep Timer") { sleepDialog() }); tools.addView(button("Volume and Sound") { soundDialog() }); card.addView(tools)
        progress = SeekBar(this).apply { max = 1000 }; card.addView(progress)
        val times = row(); elapsedView = muted("00:00"); durationView = muted("00:00"); times.addView(elapsedView, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(durationView); card.addView(times)
        val actions = row()
        actions.addView(button("⤨") { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle; toast(if (shuffle) "Перемешивание включено" else "Перемешивание выключено") })
        actions.addView(button("↻") { cycleRepeat() }); actions.addView(button("♡") { favoriteDialog() }); actions.addView(button("▱") { toast("Закладка: ${formatMs(controller?.currentPosition ?: 0)}") }); actions.addView(button("A↔B") { abDialog() }); actions.addView(button("☷") { showScreen(2) }); card.addView(actions)
        val transport = row().apply { gravity = Gravity.CENTER }
        transport.addView(button("↶") { seekBy(-10_000) }); transport.addView(button("◀") { controller?.seekToPreviousMediaItem() }); transport.addView(button("▶") { togglePlay() }); transport.addView(button("▶") { controller?.seekToNextMediaItem() }); transport.addView(button("↷") { seekBy(10_000) }); card.addView(transport)
        progress.setOnSeekBarChangeListener(seekListener { value -> controller?.duration?.takeIf { it > 0 }?.let { controller?.seekTo(it * value / 1000L) } })
        return root
    }

    private fun filesPage(): View {
        val root = scrollColumn(); header(root, "TG", "Файлы текущего плейлиста") { showMenu() }
        val tools = row(); tools.addView(button("＋ Файл") { filePicker.launch(arrayOf("audio/*")) }); tools.addView(button("＋ Папка") { folderPicker.launch(null) }); tools.addView(button("Сортировка") { sortDialog() }); root.addView(tools)
        val search = edit("Поиск файлов..."); root.addView(search); tracksView = column(); root.addView(tracksView)
        search.addTextChangedListener(SimpleTextWatcher { renderTracks(it) })
        val bottom = row(); bottom.addView(button("A↔B") { abDialog() }); bottom.addView(button("＋") { filePicker.launch(arrayOf("audio/*")) }); bottom.addView(button("⤨") { shuffle = true; controller?.shuffleModeEnabled = true }); bottom.addView(button("↻") { repeatMode = Player.REPEAT_MODE_ALL; controller?.repeatMode = repeatMode }); root.addView(bottom)
        return root
    }

    private fun drawPlaylists(container: LinearLayout, query: String) {
        container.removeAllViews(); playlists.forEachIndexed { i, p -> if (p.name.contains(query, true)) container.addView(rowButton("${if (p.favorite) "♡" else "♫"}  ${p.name}", "${p.tracks.size} треков") { selectedPlaylist = i; selectedTrack = -1; render(); showScreen(2) }.apply { setOnLongClickListener { if (i > 0) confirmDeletePlaylist(i); true } }) }
    }

    private fun render() { drawPlaylists(pages[0].findViewById(R.id.playlist_container) ?: return, "") ; updateHeaders(); renderTracks("") }

    private fun updateHeaders() { val name = current().name; titleView.text = if (selectedTrack in current().tracks.indices) current().tracks[selectedTrack].title else "Выберите трек"; artistView.text = if (selectedTrack in current().tracks.indices) current().tracks[selectedTrack].artist else "ABPlayer"; statusView.text = if (controller?.isPlaying == true) "ВОСПРОИЗВЕДЕНИЕ" else "ГОТОВ К ВОСПРОИЗВЕДЕНИЮ" }

    private fun renderTracks(query: String) {
        if (!::tracksView.isInitialized) return
        tracksView.removeAllViews(); val visible = current().tracks.filter { (it.title + " " + it.artist).contains(query, true) }
        if (visible.isEmpty()) tracksView.addView(muted("Плейлист пуст — добавьте аудиофайлы"))
        visible.forEachIndexed { display, track -> val index = current().tracks.indexOfFirst { it.id == track.id }; tracksView.addView(rowButton("${if (index == selectedTrack) "▶" else "${display + 1}."} ${track.title}", "${track.artist} • ${track.album} • ${formatMs(track.duration)}") { play(index) }.apply { setOnLongClickListener { trackMenu(index); true } }) }
    }

    private fun current(): Playlist = playlists.getOrElse(selectedPlaylist) { playlists.first() }
    private fun play(index: Int) { if (index !in current().tracks.indices) return; selectedTrack = index; val items = current().tracks.map { it.toMediaItem() }; controller?.setMediaItems(items, index, 0); controller?.prepare(); controller?.play(); render() }
    private fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else if (it.currentMediaItem == null && current().tracks.isNotEmpty()) play(0) else it.play() } }
    private fun seekBy(delta: Long) { controller?.let { it.seekTo((it.currentPosition + delta).coerceIn(0, if (it.duration > 0) it.duration else Long.MAX_VALUE)) } }
    private fun cycleRepeat() { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = repeatMode; toast("Повтор: ${if (repeatMode == Player.REPEAT_MODE_OFF) "выключен" else if (repeatMode == Player.REPEAT_MODE_ONE) "трека" else "плейлиста"}") }

    private fun sleepDialog() { val items = arrayOf("Дождаться конца трека", "Дождаться конца плейлиста", "5 минут", "15 минут", "30 минут", "1 час", "Задать время...", "Выключить таймер"); AlertDialog.Builder(this).setTitle("Остановить воспроизведение").setItems(items) { _, which -> when (which) { 0 -> { stopAfterTrack = true; stopAfterPlaylist = false; toast("Остановка после трека") }; 1 -> { stopAfterPlaylist = true; stopAfterTrack = false; toast("Остановка после плейлиста") }; 2,3,4,5 -> startTimer(longArrayOf(5,15,30,60)[which - 2]); 6 -> customTimer(); else -> cancelTimer() } }.show() }
    private fun startTimer(minutes: Long) { cancelTimer(); sleepRunnable = Runnable { controller?.pause(); toast("Таймер остановил воспроизведение") }; handler.postDelayed(sleepRunnable!!, minutes * 60_000); toast("Таймер: $minutes минут") }
    private fun customTimer() { val input = edit("Минуты"); AlertDialog.Builder(this).setTitle("Задать время").setView(input).setPositiveButton("Запустить") { _, _ -> input.text.toString().toLongOrNull()?.takeIf { it > 0 }?.let(::startTimer) }.setNegativeButton("Отмена", null).show() }
    private fun cancelTimer() { sleepRunnable?.let(handler::removeCallbacks); sleepRunnable = null; stopAfterTrack = false; stopAfterPlaylist = false; toast("Таймер выключен") }

    private fun soundDialog() { val box = column(); repeat(10) { band -> box.addView(label("Полоса ${band + 1}")); box.addView(SeekBar(this).apply { max = 24; progress = 12; setOnSeekBarChangeListener(seekListener { PlaybackService.instance?.setBandLevel(band, it - 12) }) }) }; box.addView(label("Скорость")); box.addView(SeekBar(this).apply { max = 150; progress = 100; setOnSeekBarChangeListener(seekListener { PlaybackService.instance?.setPlaybackSpeed(it / 100f) }) }); box.addView(label("Баланс L/R")); box.addView(SeekBar(this).apply { max = 200; progress = 100; setOnSeekBarChangeListener(seekListener { PlaybackService.instance?.setStereoBalance(it - 100) }) }); AlertDialog.Builder(this).setTitle("Volume and Sound / Equalizer").setView(box).setPositiveButton("Готово", null).show() }
    private fun abDialog() { val info = muted("A: ${if (aPoint == C.TIME_UNSET) "—" else formatMs(aPoint)}\nB: ${if (bPoint == C.TIME_UNSET) "—" else formatMs(bPoint)}"); AlertDialog.Builder(this).setTitle("Точки A–B").setView(info).setNegativeButton("Установить A") { _, _ -> aPoint = controller?.currentPosition ?: C.TIME_UNSET }.setPositiveButton("Установить B") { _, _ -> bPoint = controller?.currentPosition ?: C.TIME_UNSET }.setNeutralButton("Сбросить") { _, _ -> aPoint = C.TIME_UNSET; bPoint = C.TIME_UNSET }.show() }
    private fun favoriteDialog() { AlertDialog.Builder(this).setTitle("Избранное").setItems(arrayOf("Добавить/удалить текущий трек", "Открыть плейлист")) { _, which -> if (which == 0 && selectedTrack >= 0) toggleFavorite() else if (which == 1) { selectedPlaylist = 0; showScreen(2) } }.show() }
    private fun toggleFavorite() { val track = current().tracks.getOrNull(selectedTrack) ?: return; val fav = playlists[0]; if (track.favorite) { fav.tracks.removeAll { it.id == track.id }; track.favorite = false; toast("Удалено из избранного") } else { if (fav.tracks.none { it.id == track.id }) fav.tracks.add(track.copy(favorite = true)); track.favorite = true; toast("Добавлено в избранное") }; persist(); render() }
    private fun sortDialog() { AlertDialog.Builder(this).setTitle("Сортировка").setItems(arrayOf("По имени", "По длительности", "По размеру", "По дате")) { _, which -> current().tracks = when (which) { 0 -> current().tracks.sortedBy { it.title.lowercase() }.toMutableList(); 1 -> current().tracks.sortedBy { it.duration }.toMutableList(); 2 -> current().tracks.sortedBy { it.size }.toMutableList(); else -> current().tracks.sortedBy { it.date }.toMutableList() }; persist(); render() }.show() }
    private fun trackMenu(index: Int) { AlertDialog.Builder(this).setItems(arrayOf("Воспроизвести", "Удалить из плейлиста", "Добавить в избранное")) { _, which -> when (which) { 0 -> play(index); 1 -> { current().tracks.removeAt(index); if (selectedTrack == index) selectedTrack = -1; persist(); render() }; 2 -> { val t = current().tracks[index]; if (playlists[0].tracks.none { it.id == t.id }) playlists[0].tracks.add(t.copy(favorite = true)); persist(); render() } } }.show() }
    private fun confirmDeletePlaylist(index: Int) { AlertDialog.Builder(this).setTitle("Удалить плейлист?").setMessage(playlists[index].name).setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ -> playlists.removeAt(index); selectedPlaylist = selectedPlaylist.coerceAtMost(playlists.lastIndex); persist(); recreate() }.show() }
    private fun showMenu() { AlertDialog.Builder(this).setTitle("Меню").setItems(arrayOf("Настройки и плейлисты", "Файлы текущего плейлиста", "Настройки звука")) { _, i -> when (i) { 0 -> showScreen(0); 1 -> showScreen(2); else -> soundDialog() } }.show() }
    private fun createPlaylist() { val input = edit("Название"); AlertDialog.Builder(this).setTitle("Новый плейлист").setView(input).setPositiveButton("Создать") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { playlists.add(Playlist(it)); selectedPlaylist = playlists.lastIndex; persist(); recreate() } }.setNegativeButton("Отмена", null).show() }

    private fun connectController() { val token = SessionToken(this, ComponentName(this, PlaybackService::class.java)); controllerFuture = MediaController.Builder(this, token).buildAsync(); controllerFuture.addListener({ try { controller = controllerFuture.get().also { it.addListener(playerListener); it.repeatMode = repeatMode; it.shuffleModeEnabled = shuffle } } catch (_: Exception) {} }, ContextCompat.getMainExecutor(this)) }
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { selectedTrack = controller?.currentMediaItemIndex ?: -1; render() }
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { if (stopAfterTrack || (stopAfterPlaylist && controller?.currentMediaItemIndex == current().tracks.lastIndex)) controller?.pause() } }
        override fun onIsPlayingChanged(isPlaying: Boolean) { updateHeaders() }
    }
    private fun updatePlayerUi(player: Player) { elapsedView.text = formatMs(player.currentPosition); durationView.text = formatMs(player.duration); progress.progress = if (player.duration > 0) (player.currentPosition * 1000 / player.duration).toInt() else 0; if (aPoint != C.TIME_UNSET && bPoint != C.TIME_UNSET && player.currentPosition >= bPoint && bPoint > aPoint) player.seekTo(aPoint) }
    private fun updateHeaders() { val track = current().tracks.getOrNull(selectedTrack); titleView.text = track?.title ?: "Выберите трек"; artistView.text = track?.artist ?: "ABPlayer"; statusView.text = if (controller?.isPlaying == true) "ВОСПРОИЗВЕДЕНИЕ" else "ГОТОВ К ВОСПРОИЗВЕДЕНИЮ" }

    private fun loadPlaylists() { playlists.clear(); try { val a = JSONArray(prefs.getString("playlists", "[]")); for (i in 0 until a.length()) { val o=a.getJSONObject(i); val p=Playlist(o.getString("name"),o.optBoolean("favorite")); val ts=o.optJSONArray("tracks") ?: JSONArray(); for (j in 0 until ts.length()) { val t=ts.getJSONObject(j); p.tracks.add(Track(t.getString("id"),t.getString("title"),t.optString("artist"),t.optString("album"),t.getString("uri"),t.optLong("duration"),t.optLong("size"),t.optLong("date"),t.optBoolean("favorite"))) }; playlists.add(p) } } catch (_: Exception) {} ; if (playlists.isEmpty()) { playlists.add(Playlist("Избранное", true)); playlists.add(Playlist("Архитектура")); playlists.add(Playlist("Работа")) } }
    private fun persist() { val root=JSONArray(); playlists.forEach { p -> val o=JSONObject().put("name",p.name).put("favorite",p.favorite); val ts=JSONArray(); p.tracks.forEach { t -> ts.put(JSONObject().put("id",t.id).put("title",t.title).put("artist",t.artist).put("album",t.album).put("uri",t.uri).put("duration",t.duration).put("size",t.size).put("date",t.date).put("favorite",t.favorite)) }; o.put("tracks",ts); root.put(o) }; prefs.edit().putString("playlists",root.toString()).apply() }
    private fun addUriIfMissing(uri: Uri) { val id=uri.toString(); if (current().tracks.none { it.id == id }) current().tracks.add(Track(id, queryName(uri) ?: "Без названия", "Неизвестный исполнитель", "Локальный файл", id, size = querySize(uri), date = System.currentTimeMillis())) }
    private fun addTrackIfMissing(t: Track) { if (current().tracks.none { it.id == t.id }) current().tracks.add(t) }
    private fun takeReadPermission(uri: Uri) { try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
    private fun queryName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun querySize(uri: Uri): Long = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L
    private fun showScreen(n: Int) { screen=n.coerceIn(0,2); pager.post { pager.translationX = -screen * pager.width.toFloat() }; if (screen == 2) renderTracks("") }
    private fun scrollColumn()=ScrollView(this).apply { addView(column()) }.let { it.getChildAt(0) as LinearLayout }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(28)); setBackgroundColor(bgColor()) }
    private fun cardColumn()=column().apply { setPadding(dp(14),dp(14),dp(14,)); setBackgroundColor(panelColor()) }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun header(parent:LinearLayout,title:String,sub:String,onClick:()->Unit){val r=row();val c=column().apply{setPadding(0,0,0,0)};c.addView(text(title,20f));c.addView(muted(sub));r.addView(c,LinearLayout.LayoutParams(0,-2,1f));r.addView(button("⋮",onClick));parent.addView(r)}
    private fun button(s:String,action:()->Unit)=Button(this).apply{text=s;setTextColor(fgColor());setOnClickListener{action()};setBackgroundColor(Color.TRANSPARENT)}
    private fun rowButton(a:String,b:String,action:()->Unit)=button("$a\n$b",action).apply{gravity=Gravity.START;setPadding(dp(12),dp(12),dp(8),dp(12));setBackgroundColor(panelColor())}
    private fun edit(h:String)=EditText(this).apply{hint=h;setHintTextColor(mutedColor());setTextColor(fgColor());setSingleLine(true)}
    private fun text(s:String,size:Float)=TextView(this).apply{text=s;textSize=size;setTextColor(fgColor())}
    private fun muted(s:String)=TextView(this).apply{text=s;textSize=12f;setTextColor(mutedColor())}
    private fun label(s:String)=TextView(this).apply{text=s;setTextColor(Color.rgb(105,169,255));setPadding(0,dp(8),0,dp(5))}
    private fun section(s:String)=TextView(this).apply{text=s;setTextColor(Color.rgb(143,167,194));setPadding(0,dp(18),0,dp(7))}
    private fun controlRow(name:String,control:View)=row().apply{addView(text(name,14f),LinearLayout.LayoutParams(0,-2,1f));addView(control)}
    private fun settingRow(name:String,initial:Boolean,changed:(Boolean)->Unit)=controlRow(name,Switch(this).apply{isChecked=initial;setOnCheckedChangeListener{_,v->changed(v)}})
    private fun seekListener(action:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(f)action(p)};override fun onStartTrackingTouch(s:SeekBar?){ };override fun onStopTrackingTouch(s:SeekBar?){ }}
    private fun formatMs(ms:Long)=if(ms<=0)"00:00" else String.format(Locale.US,"%02d:%02d",ms/60000,(ms/1000)%60)
    private fun bgColor()=if(light)Color.rgb(242,245,249) else Color.rgb(7,11,17);private fun panelColor()=if(light)Color.WHITE else Color.rgb(18,25,34);private fun fgColor()=if(light)Color.rgb(23,34,53) else Color.rgb(243,247,255);private fun mutedColor()=if(light)Color.rgb(99,116,138) else Color.rgb(156,175,196);private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt();private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onDestroy(){handler.removeCallbacks(progressUpdater);sleepRunnable?.let(handler::removeCallbacks);if(::controllerFuture.isInitialized)MediaController.releaseFuture(controllerFuture);super.onDestroy()}
}

data class Playlist(val name:String,val favorite:Boolean=false,var tracks:MutableList<Track> = mutableListOf())
private class SimpleTextWatcher(private val callback:(String)->Unit):android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int)=callback(s?.toString().orEmpty());override fun afterTextChanged(e:android.text.Editable?){}}
private fun Track.toMediaItem()=MediaItem.Builder().setMediaId(id).setUri(Uri.parse(uri)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
