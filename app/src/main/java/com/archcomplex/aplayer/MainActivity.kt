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
    private val prefs by lazy { getSharedPreferences("abplayer", Context.MODE_PRIVATE) }
    private val pages = mutableListOf<View>()
    private lateinit var pager: LinearLayout
    private lateinit var trackContainer: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var nowStatus: TextView
    private lateinit var progress: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var volume: SeekBar
    private var page = 1
    private var playlistIndex = 0
    private var trackIndex = -1
    private var shuffle = false
    private var repeat = Player.REPEAT_MODE_OFF
    private var aPoint = C.TIME_UNSET
    private var bPoint = C.TIME_UNSET
    private var timer: Runnable? = null
    private var playlists = mutableListOf<Playlist>()
    private var lightTheme = false
    private var downX = 0f

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { takeRead(it); addToCurrent(it) }; persist(); renderAll()
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        takeRead(uri); FolderLibrary.addFolder(this, uri)
        Thread { FolderLibrary.scan(this).forEach { addTrackIfMissing(it) }; runOnUiThread { persist(); renderAll() } }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        lightTheme = prefs.getBoolean("light", false)
        playlists = loadPlaylists().toMutableList()
        if (playlists.isEmpty()) playlists = mutableListOf(Playlist("Избранное", true), Playlist("Архитектура"), Playlist("Работа"))
        buildUi(); connectController(); page = 1; showPage(1); renderAll()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = event.rawX
            MotionEvent.ACTION_UP -> { val dx = event.rawX - downX; if (abs(dx) > 90 && abs(dx) > abs(event.rawY)) { showPage(page + if (dx < 0) 1 else -1); return true } }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun buildUi() {
        pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = solid(bg()) }
        pages += settingsPage(); pages += playerPage(); pages += filesPage()
        pages.forEach { pager.addView(it, LinearLayout.LayoutParams(-1, -1)) }
        setContentView(pager)
        window.statusBarColor = bg(); window.navigationBarColor = bg()
    }

    private fun settingsPage(): View {
        val root = scrollColumn(); header(root, "ABPlayer", "Настройки и плейлисты") { menu() }
        root.addView(label("ПОИСК ПЛЕЙЛИСТОВ")); val search = edit("Поиск плейлистов..."); root.addView(search)
        root.addView(section("ПЛЕЙЛИСТЫ")); val list = column(); root.addView(list)
        fun draw() { list.removeAllViews(); val q = search.text.toString().lowercase(); playlists.forEachIndexed { i, p -> if (p.name.lowercase().contains(q)) list.addView(playlistRow(i, p)) } }
        search.addTextChangedListener(SimpleTextWatcher { draw() }); draw()
        root.addView(section("НАСТРОЙКИ ВОСПРОИЗВЕДЕНИЯ")); val settings = column(); root.addView(settings)
        listOf("Восстанавливать позицию","Предзагрузка следующего трека","Плавное начало и окончание","Кроссфейд","ReplayGain","Нормализация громкости").forEachIndexed { i, s -> settings.addView(settingRow(s, prefs.getBoolean("setting_$i", i != 3)) { v -> prefs.edit().putBoolean("setting_$i", v).apply() }) }
        root.addView(section("ИНТЕРФЕЙС")); val ui = column(); root.addView(ui)
        ui.addView(controlRow("Тема", button(if (lightTheme) "Light Theme" else "Dark Theme") { lightTheme = !lightTheme; prefs.edit().putBoolean("light", lightTheme).apply(); recreate() }))
        ui.addView(settingRow("Анимации", true) { }); ui.addView(settingRow("Автоповорот", true) { })
        root.addView(button("＋ Создать плейлист") { createPlaylist() })
        return root
    }

    private fun playerPage(): View {
        val root = scrollColumn(); header(root, "ABPlayer", "Избранное") { menu() }
        val cover = TextView(this).apply { text = "♫"; textSize = 88f; gravity = Gravity.CENTER; setTextColor(Color.rgb(220,235,255)); background = solid(Color.rgb(28,42,61)) }
        root.addView(cover, LinearLayout.LayoutParams(-1, dp(230)).apply { bottomMargin = dp(16) })
        val card = cardColumn(); root.addView(card)
        nowStatus = label("ГОТОВ К ВОСПРОИЗВЕДЕНИЮ"); card.addView(nowStatus)
        nowTitle = text("Выберите трек", 25f); card.addView(nowTitle); nowArtist = muted("ABPlayer"); card.addView(nowArtist)
        val tools = row(); tools.addView(button("Sleep Timer") { sleepDialog() }); tools.addView(button("Volume and Sound") { soundDialog() }); card.addView(tools)
        progress = SeekBar(this).apply { max = 1000 }; card.addView(progress)
        val times = row(); elapsed = muted("00:00"); duration = muted("00:00"); times.addView(elapsed, LinearLayout.LayoutParams(0,-2,1f)); times.addView(duration); card.addView(times)
        val actions = row(); actions.addView(button("⤨") { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle; toast(if(shuffle) "Перемешивание включено" else "Перемешивание выключено") }); actions.addView(button("↻") { cycleRepeat() }); actions.addView(button("♡") { favoriteDialog() }); actions.addView(button("▱") { toast("Закладка сохранена") }); actions.addView(button("A↔B") { abDialog() }); actions.addView(button("☷") { showPage(2) }); card.addView(actions)
        val transport = row(); transport.gravity = Gravity.CENTER; transport.addView(button("↶") { seek(-10_000) }); transport.addView(button("◀") { controller?.seekToPreviousMediaItem() }); transport.addView(button("▶") { togglePlay() }.apply { textSize = 24f }); transport.addView(button("▶") { controller?.seekToNextMediaItem() }); transport.addView(button("↷") { seek(10_000) }); card.addView(transport)
        volume = SeekBar(this).apply { max = 100; progress = prefs.getInt("volume", 72); setOnSeekBarChangeListener(seekListener { setVolume(it) }) }; card.addView(volume)
        progress.setOnSeekBarChangeListener(seekListener { c -> controller?.duration?.takeIf { it > 0 }?.let { controller?.seekTo(it * c / 1000L) } })
        return root
    }

    private fun filesPage(): View {
        val root = scrollColumn(); header(root, "TG", "Файлы текущего плейлиста") { menu() }
        val tools = row(); tools.addView(button("＋ Файл") { pickFiles.launch(arrayOf("audio/*")) }); tools.addView(button("＋ Папка") { pickFolder.launch(null) }); tools.addView(button("Сортировка") { sortDialog() }); root.addView(tools)
        val search = edit("Поиск файлов..."); root.addView(search); trackContainer = column(); root.addView(trackContainer); search.addTextChangedListener(SimpleTextWatcher { renderTracks(it) })
        val bottom = row(); bottom.addView(button("A↔B") { abDialog() }); bottom.addView(button("＋") { pickFiles.launch(arrayOf("audio/*")) }); bottom.addView(button("⤨") { shuffle = true; controller?.shuffleModeEnabled = true; toast("Перемешивание включено") }); bottom.addView(button("↻") { repeat = Player.REPEAT_MODE_ALL; controller?.repeatMode = repeat }); root.addView(bottom)
        return root
    }

    private fun renderAll() { updateHeaders(); renderTracks("") }
    private fun updateHeaders() { val name = playlists.getOrNull(playlistIndex)?.name ?: "Избранное"; pages.getOrNull(1)?.let { (it as ViewGroup).findViewsByText("Избранное").forEach { v -> (v as? TextView)?.text = name } } }
    private fun renderTracks(query: String) {
        if (!::trackContainer.isInitialized) return
        trackContainer.removeAllViews(); val list = current().tracks.filter { (it.title + " " + it.artist).contains(query, true) }
        if (list.isEmpty()) { trackContainer.addView(muted("Плейлист пуст — добавьте аудиофайлы")); return }
        list.forEachIndexed { display, t -> val original = current().tracks.indexOfFirst { it.id == t.id }; trackContainer.addView(trackRow(t, original, display)) }
    }

    private fun trackRow(t: Track, index: Int, display: Int): View = rowButton("${if(index == trackIndex) "▶" else "${display + 1}."} ${t.title}", "${t.artist} • ${t.album}") { play(index) }.apply { setOnLongClickListener { trackMenu(index); true } }
    private fun playlistRow(i: Int, p: Playlist): View = rowButton("${if(p.favorite) "♡" else "♫"}  ${p.name}", "${p.tracks.size} треков") { playlistIndex = i; trackIndex = -1; renderAll(); showPage(2) }.apply { setOnLongClickListener { if(i > 0) { playlists.removeAt(i); playlistIndex = playlistIndex.coerceAtMost(playlists.lastIndex); persist(); renderAll() }; true } }

    private fun play(index: Int) { if (index !in current().tracks.indices) return; trackIndex = index; val items = current().tracks.map { it.toMediaItem() }; controller?.setMediaItems(items, index, 0); controller?.prepare(); controller?.play(); updateNowPlaying(current().tracks[index]); renderTracks("") }
    private fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else { if (it.currentMediaItem == null && current().tracks.isNotEmpty()) play(0) else it.play() } } }
    private fun setVolume(v: Int) { prefs.edit().putInt("volume", v).apply(); PlaybackService.instance?.setVolumePercent(v) }
    private fun cycleRepeat() { repeat = when(repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = repeat; toast("Повтор: ${if(repeat == Player.REPEAT_MODE_OFF) "выключен" else if(repeat == Player.REPEAT_MODE_ONE) "трека" else "плейлиста"}") }
    private fun seek(delta: Long) { controller?.let { it.seekTo((it.currentPosition + delta).coerceIn(0, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) } }

    private fun sleepDialog() { val opts = arrayOf("Дождаться конца трека","5 минут","15 минут","30 минут","1 час","Задать время...","Выключить таймер"); AlertDialog.Builder(this).setTitle("Остановить воспроизведение").setItems(opts) { _, i -> when(i) { 0 -> toast("Таймер сработает после трека"); 1,2,3,4 -> startTimer(longArrayOf(5,15,30,60)[i-1]); 5 -> customTimer(); else -> cancelTimer() } }.show() }
    private fun startTimer(minutes: Long) { cancelTimer(); timer = Runnable { controller?.pause(); toast("Таймер остановил воспроизведение") }; handler.postDelayed(timer!!, minutes * 60_000); toast("Таймер: $minutes минут") }
    private fun customTimer() { val input = edit("Минуты"); AlertDialog.Builder(this).setTitle("Задать время").setView(input).setPositiveButton("Запустить") { _, _ -> input.text.toString().toLongOrNull()?.takeIf { it > 0 }?.let(::startTimer) }.setNegativeButton("Отмена", null).show() }
    private fun cancelTimer() { timer?.let(handler::removeCallbacks); timer = null; toast("Таймер выключен") }
    private fun soundDialog() { val box = column(); val eq = (0 until 10).map { band -> SeekBar(this).apply { max = 24; progress = 12; setOnSeekBarChangeListener(seekListener { PlaybackService.instance?.setBandLevel(band, it - 12) }) } }; eq.forEach { box.addView(it) }; box.addView(label("Скорость")); box.addView(SeekBar(this).apply { max=150; progress=100; setOnSeekBarChangeListener(seekListener { PlaybackService.instance?.setPlaybackSpeed(it / 100f) }) }); box.addView(label("Баланс L/R")); box.addView(SeekBar(this).apply { max=200; progress=100; setOnSeekBarChangeListener(seekListener { PlaybackService.instance?.setStereoBalance(it - 100) }) }); AlertDialog.Builder(this).setTitle("Volume and Sound / Equalizer").setView(box).setPositiveButton("Готово", null).show() }
    private fun abDialog() { val box = column(); box.addView(muted("A: ${if(aPoint == C.TIME_UNSET) "—" else fmt(aPoint)}\nB: ${if(bPoint == C.TIME_UNSET) "—" else fmt(bPoint)}")); AlertDialog.Builder(this).setTitle("Точки A–B").setView(box).setNegativeButton("Установить A") { _, _ -> aPoint = controller?.currentPosition ?: C.TIME_UNSET }.setPositiveButton("Установить B") { _, _ -> bPoint = controller?.currentPosition ?: C.TIME_UNSET }.setNeutralButton("Сбросить") { _, _ -> aPoint = C.TIME_UNSET; bPoint = C.TIME_UNSET }.show() }
    private fun favoriteDialog() { AlertDialog.Builder(this).setTitle("Избранное").setItems(arrayOf("Добавить/удалить текущий трек","Открыть плейлист")) { _, i -> if(i==0 && trackIndex >= 0) toggleFavorite() else if(i==1) { playlistIndex=0; showPage(2) } }.show() }
    private fun toggleFavorite() { val t=current().tracks[trackIndex]; val fav=playlists[0]; if(t.favorite) { fav.tracks.removeAll { it.id == t.id }; t.favorite=false; toast("Удалено из избранного") } else { if(fav.tracks.none { it.id==t.id }) fav.tracks.add(t.copy(favorite=true)); t.favorite=true; toast("Добавлено в избранное") }; persist(); renderAll() }
    private fun sortDialog() { AlertDialog.Builder(this).setTitle("Сортировка").setItems(arrayOf("По имени","По длительности","По размеру","По дате")) { _, i -> current().tracks = when(i) { 0 -> current().tracks.sortedBy { it.title.lowercase() }.toMutableList(); 1 -> current().tracks.sortedBy { it.duration }; 2 -> current().tracks.sortedBy { it.size }; else -> current().tracks.sortedBy { it.date }.toMutableList() }; persist(); renderAll() }.show() }
    private fun trackMenu(index: Int) { AlertDialog.Builder(this).setItems(arrayOf("Воспроизвести","Удалить из плейлиста","Добавить в избранное")) { _, i -> when(i) { 0 -> play(index); 1 -> { current().tracks.removeAt(index); if(trackIndex==index) trackIndex=-1; persist(); renderAll() }; 2 -> { val t=current().tracks[index]; if(playlists[0].tracks.none { it.id==t.id }) playlists[0].tracks.add(t.copy(favorite=true)); persist(); renderAll() } } }.show() }
    private fun menu() { AlertDialog.Builder(this).setTitle("Меню").setItems(arrayOf("Настройки и плейлисты","Файлы текущего плейлиста","Настройки звука")) { _, i -> when(i) { 0 -> showPage(0); 1 -> showPage(2); else -> soundDialog() } }.show() }
    private fun createPlaylist() { val input=edit("Название"); AlertDialog.Builder(this).setTitle("Новый плейлист").setView(input).setPositiveButton("Создать") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { playlists.add(Playlist(it)); playlistIndex=playlists.lastIndex; persist(); recreate() } }.setNegativeButton("Отмена", null).show() }

    private fun connectController() { val token=SessionToken(this, ComponentName(this, PlaybackService::class.java)); controllerFuture=MediaController.Builder(this, token).buildAsync(); controllerFuture.addListener({ try { controller=controllerFuture.get(); controller?.addListener(object: Player.Listener { override fun onEvents(player: Player, events: Player.Events) { handler.post { updateFromPlayer(player) } } }); controller?.repeatMode=repeat; controller?.shuffleModeEnabled=shuffle } catch(_:Exception){} }, ContextCompat.getMainExecutor(this)) }
    private fun updateFromPlayer(player: Player) { if(player.currentMediaItemIndex in current().tracks.indices) { trackIndex=player.currentMediaItemIndex; updateNowPlaying(current().tracks[trackIndex]) }; elapsed.text=fmt(player.currentPosition); duration.text=fmt(player.duration); progress.progress=if(player.duration>0) (player.currentPosition*1000/player.duration).toInt() else 0; if(aPoint != C.TIME_UNSET && bPoint != C.TIME_UNSET && player.currentPosition >= bPoint) player.seekTo(aPoint) }
    private fun updateNowPlaying(t: Track) { nowTitle.text=t.title; nowArtist.text=t.artist; nowStatus.text="ВОСПРОИЗВЕДЕНИЕ" }

    private fun persist() { val root=JSONArray(); playlists.forEach { p -> val o=JSONObject().put("name",p.name).put("favorite",p.favorite); val a=JSONArray(); p.tracks.forEach { t -> a.put(JSONObject().put("id",t.id).put("title",t.title).put("artist",t.artist).put("album",t.album).put("uri",t.uri).put("duration",t.duration).put("size",t.size).put("date",t.date).put("favorite",t.favorite)) }; o.put("tracks",a); root.put(o) }; prefs.edit().putString("playlists",root.toString()).apply() }
    private fun loadPlaylists(): List<Playlist> = try { val a=JSONArray(prefs.getString("playlists","[]")); (0 until a.length()).map { val o=a.getJSONObject(it); val p=Playlist(o.getString("name"),o.optBoolean("favorite")); val ts=o.optJSONArray("tracks") ?: JSONArray(); (0 until ts.length()).forEach { j -> val t=ts.getJSONObject(j); p.tracks.add(Track(t.getString("id"),t.getString("title"),t.optString("artist"),t.optString("album"),t.getString("uri"),t.optLong("duration"),t.optLong("size"),t.optLong("date"),t.optBoolean("favorite"))) }; p } } catch(_:Exception) { emptyList() }
    private fun addToCurrent(uri: Uri) { val id=uri.toString(); if(current().tracks.none { it.id==id }) { val name=queryName(uri) ?: "Без названия"; current().tracks.add(Track(id,name,"Неизвестный исполнитель","Локальный файл",id,size=querySize(uri),date=System.currentTimeMillis())) } }
    private fun addTrackIfMissing(t: Track) { runOnUiThread { if(current().tracks.none { it.id==t.id }) current().tracks.add(t) } }
    private fun queryName(uri: Uri): String? = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
    private fun querySize(uri: Uri): Long = contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L
    private fun takeRead(uri: Uri) { try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception){} }

    private fun showPage(n:Int) { page=n.coerceIn(0,2); pager.translationX=-page*pager.width.toFloat() }
    private fun scrollColumn()=ScrollView(this).apply { addView(column()) }.let { it.getChildAt(0) as LinearLayout }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(28)); background=solid(bg()) }
    private fun cardColumn()=column().apply { background=solid(panel()); setPadding(dp(14),dp(14),dp(14),dp(14)) }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun header(parent:LinearLayout,title:String,sub:String,onClick:()->Unit) { val r=row(); val c=column().apply { setPadding(0,0,0,0) }; c.addView(text(title,20f)); c.addView(muted(sub)); r.addView(c,LinearLayout.LayoutParams(0,-2,1f)); r.addView(button("⋮",onClick)); parent.addView(r) }
    private fun button(s:String,click:()->Unit)=Button(this).apply { text=s; setTextColor(fg()); setOnClickListener { click() }; background=solid(Color.TRANSPARENT) }
    private fun rowButton(a:String,b:String,click:()->Unit)=button("$a\n$b",click).apply { gravity=Gravity.START; setPadding(dp(12),dp(12),dp(8),dp(12)); background=solid(panel()) }
    private fun edit(h:String)=EditText(this).apply { hint=h; setHintTextColor(muted()); setTextColor(fg()); setSingleLine(true) }
    private fun label(s:String)=TextView(this).apply { text=s; setTextColor(Color.rgb(105,169,255)); setPadding(0,dp(8),0,dp(5)) }
    private fun section(s:String)=TextView(this).apply { text=s; setTextColor(Color.rgb(143,167,194)); setPadding(0,dp(18),0,dp(7)) }
    private fun text(s:String,size:Float)=TextView(this).apply { text=s; textSize=size; setTextColor(fg()) }
    private fun muted(s:String)=TextView(this).apply { text=s; textSize=12f; setTextColor(muted()) }
    private fun controlRow(name:String, control:View)=row().apply { addView(text(name,14f),LinearLayout.LayoutParams(0,-2,1f)); addView(control) }
    private fun settingRow(name:String, initial:Boolean, changed:(Boolean)->Unit):View { val sw=Switch(this).apply { isChecked=initial; setOnCheckedChangeListener { _,v -> changed(v) } }; return controlRow(name,sw) }
    private fun seekListener(action:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){ if(f) action(p) }; override fun onStartTrackingTouch(s:SeekBar?){ }; override fun onStopTrackingTouch(s:SeekBar?){ } }
    private fun fmt(ms:Long)=if(ms<=0) "00:00" else String.format(Locale.US,"%02d:%02d",ms/60000,(ms/1000)%60)
    private fun bg()=if(lightTheme) Color.rgb(242,245,249) else Color.rgb(7,11,17); private fun panel()=if(lightTheme) Color.WHITE else Color.rgb(18,25,34); private fun fg()=if(lightTheme) Color.rgb(23,34,53) else Color.rgb(243,247,255); private fun muted()=if(lightTheme) Color.rgb(99,116,138) else Color.rgb(156,175,196); private fun solid(c:Int)=android.graphics.drawable.ColorDrawable(c); private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt(); private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onDestroy(){ timer?.let(handler::removeCallbacks); if(::controllerFuture.isInitialized) MediaController.releaseFuture(controllerFuture); super.onDestroy() }
}

data class Playlist(val name:String, val favorite:Boolean=false, var tracks:MutableList<Track> = mutableListOf())
private class SimpleTextWatcher(val changed:(String)->Unit): android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){}; override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int)=changed(s?.toString().orEmpty()); override fun afterTextChanged(e:android.text.Editable?){} }
private fun Track.toMediaItem()=MediaItem.Builder().setMediaId(id).setUri(Uri.parse(uri)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
