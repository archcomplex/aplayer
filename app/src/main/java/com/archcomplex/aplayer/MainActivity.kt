package com.archcomplex.aplayer

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pages = mutableListOf<LinearLayout>()
    private lateinit var pager: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var statusView: TextView
    private lateinit var progress: SeekBar
    private lateinit var timeView: TextView
    private lateinit var trackList: LinearLayout
    private var page = 1 // The player is deliberately the first screen.
    private var currentPlaylist = 0
    private var currentTrack = -1
    private var shuffle = false
    private var repeat = PlayerRepeat.OFF
    private var tracks = mutableListOf<Track>()
    private val playlists = mutableListOf("Избранное", "Архитектура", "Работа")
    private var timerRunnable: Runnable? = null
    private var pointA: Long? = null
    private var pointB: Long? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { persistRead(it); addTrack(it) }
        refreshFiles()
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        persistRead(uri); FolderLibrary.addFolder(this, uri); loadFolderTracks()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        buildUi()
        connectPlayback()
        loadFolderTracks()
        showPage(1) // Always open on the main player.
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(7, 11, 17)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg()) }
        pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        pages += settingsPage(); pages += playerPage(); pages += filesPage(); pages.forEach { pager.addView(it, LinearLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        pager.setOnTouchListener(SwipeListener())
    }

    private fun settingsPage(): LinearLayout {
        val box = pageContainer(); header(box, "ABPlayer", "Настройки и плейлисты", "⋮") { showMenu() }
        box.addView(label("ПОИСК ПЛЕЙЛИСТОВ")); val search = EditText(this).styled("Поиск плейлистов..."); box.addView(search)
        box.addView(sectionTitle("ПЛЕЙЛИСТЫ")); val list = LinearLayout(this).vertical(); box.addView(list)
        val draw = { list.removeAllViews(); playlists.forEachIndexed { i, name ->
            val row = rowButton("♫  $name", "${if (i == 0) "Избранное" else "Пользовательский плейлист"}") { currentPlaylist=i; if (i != 0) tracks.clear(); refreshFiles(); showPage(2) }; list.addView(row) }
        }; draw(); search.setOnEditorActionListener { _,_,_ -> draw(); false }
        box.addView(sectionTitle("НАСТРОЙКИ ВОСПРОИЗВЕДЕНИЯ")); val settings = LinearLayout(this).vertical(); box.addView(settings)
        listOf("Восстанавливать позицию","Предзагрузка следующего трека","Плавное начало и окончание","Кроссфейд","ReplayGain","Нормализация громкости").forEachIndexed { i, text -> settings.addView(switchRow(text, i != 3)) }
        box.addView(sectionTitle("ИНТЕРФЕЙС")); val interfaceBox=LinearLayout(this).vertical(); box.addView(interfaceBox)
        val theme = Button(this).styled(if (isLight()) "Light Theme" else "Dark Theme"); interfaceBox.addView(rowWithControl("Тема", theme) { toggleTheme(theme) })
        interfaceBox.addView(switchRow("Анимации", true)); interfaceBox.addView(switchRow("Автоповорот", true))
        val addPlaylist = Button(this).styled("＋ Новый плейлист"); addPlaylist.setOnClickListener { newPlaylist(draw) }; box.addView(addPlaylist)
        return box
    }

    private fun playerPage(): LinearLayout {
        val box=pageContainer(); header(box,"ABPlayer","Избранное","⋮") { showMenu() }
        val cover=TextView(this).apply { text="♫"; textSize=82f; gravity=Gravity.CENTER; setTextColor(Color.rgb(220,235,255)); backgroundColor(Color.rgb(27,40,58)) }
        box.addView(cover, LinearLayout.LayoutParams(-1,220).apply { setMargins(0,0,0,16) })
        val card=LinearLayout(this).vertical().card(); box.addView(card)
        statusView=label("ГОТОВ К ВОСПРОИЗВЕДЕНИЮ"); card.addView(statusView); titleView=TextView(this).apply { text="Выберите трек"; textSize=25f; setTextColor(fg()) }; card.addView(titleView); artistView=muted("ABPlayer"); card.addView(artistView)
        val toolbar=LinearLayout(this).horizontal(); card.addView(toolbar); toolbar.addView(button("Sleep Timer") { showTimer() }); toolbar.addView(button("Volume and Sound") { showSound() })
        progress=SeekBar(this); card.addView(progress); timeView=muted("00:00                         00:00"); card.addView(timeView)
        val actions=LinearLayout(this).horizontal(); card.addView(actions); actions.addView(button("⤨") { shuffle=!shuffle; toast(if(shuffle) "Перемешивание включено" else "Перемешивание выключено") }); actions.addView(button("↻") { repeat=if(repeat==PlayerRepeat.OFF) PlayerRepeat.ALL else if(repeat==PlayerRepeat.ALL) PlayerRepeat.ONE else PlayerRepeat.OFF; toast("Повтор изменён") }); actions.addView(button("♡") { showFavorite() }); actions.addView(button("▱") { toast("Закладка сохранена") }); actions.addView(button("A↔B") { showAb() }); actions.addView(button("☷") { showPage(2) })
        val transport=LinearLayout(this).horizontal().apply { gravity=Gravity.CENTER }; card.addView(transport); transport.addView(button("↶") { seekBy(-10000) }); transport.addView(button("◀") { previous() }); val play=button("▶") { togglePlay() }.apply { textSize=24f }; transport.addView(play); transport.addView(button("▶") { next() }); transport.addView(button("↷") { seekBy(10000) })
        val vol=SeekBar(this).apply { max=100; progress=72; setOnSeekBarChangeListener(seek { PlaybackService.instance?.setVolumePercent(it) }) }; card.addView(vol)
        progress.setOnSeekBarChangeListener(seek { controller?.duration?.takeIf { it>0 }?.let { controller?.seekTo((it*progress.progress/1000f).toLong()) } })
        return box
    }

    private fun filesPage(): LinearLayout {
        val box=pageContainer(); header(box,"TG","Файлы текущего плейлиста","⋮") { showMenu() }
        val tools=LinearLayout(this).horizontal(); box.addView(tools); tools.addView(button("＋ Файл") { filePicker.launch(arrayOf("audio/*")) }); tools.addView(button("＋ Папка") { folderPicker.launch(null) }); tools.addView(button("Сортировка") { showSort() })
        val search=EditText(this).styled("Поиск файлов..."); box.addView(search); trackList=LinearLayout(this).vertical(); box.addView(ScrollView(this).apply { addView(trackList) }, LinearLayout.LayoutParams(-1,0,1f)); search.setOnEditorActionListener { _,_,_ -> refreshFiles(); false }
        val bottom=LinearLayout(this).horizontal(); box.addView(bottom); bottom.addView(button("A↔B") { showAb() }); bottom.addView(button("⤨") { shuffle=true; toast("Перемешивание включено") }); bottom.addView(button("↻") { repeat=PlayerRepeat.ALL; toast("Повтор плейлиста") }); bottom.addView(button("⌕") { search.requestFocus() })
        return box
    }

    private fun refreshFiles() { if (!::trackList.isInitialized) return; trackList.removeAllViews(); tracks.forEachIndexed { i,t -> trackList.addView(rowButton("${if(i==currentTrack) "▶" else "${i+1}."} ${t.title}", "${t.artist} • ${t.album}") { play(i) }) }; if(tracks.isEmpty()) trackList.addView(muted("Плейлист пуст — добавьте аудиофайлы")); }
    private fun addTrack(uri: Uri) { val name=queryName(uri) ?: "Без названия"; tracks.add(Track(uri.toString(),name,"Неизвестный исполнитель","Локальный файл",uri.toString())) }
    private fun loadFolderTracks() { Thread { val found=FolderLibrary.scan(this); runOnUiThread { tracks.clear(); tracks.addAll(found); refreshFiles() } }.start() }
    private fun play(i:Int) { if(i !in tracks.indices) return; currentTrack=i; val items=tracks.map { it.toMediaItem() }; controller?.setMediaItems(items,i,0); controller?.prepare(); controller?.play(); titleView.text=tracks[i].title; artistView.text=tracks[i].artist; statusView.text="ВОСПРОИЗВЕДЕНИЕ"; refreshFiles() }
    private fun togglePlay() { controller?.let { if(it.isPlaying) it.pause() else { it.prepare(); it.play() } } }
    private fun next() { controller?.let { if(shuffle) it.seekToDefaultPosition((tracks.indices.randomOrNull() ?: 0)) else it.seekToNextMediaItem(); it.play() } }
    private fun previous() { controller?.seekToPreviousMediaItem() }
    private fun seekBy(ms:Long) { controller?.let { it.seekTo((it.currentPosition+ms).coerceIn(0,it.duration.coerceAtLeast(0))) } }

    private fun showTimer() { val options=arrayOf("Дождаться конца трека","5 минут","15 минут","30 минут","1 час","Задать время...","Выключить таймер"); AlertDialog.Builder(this).setTitle("Остановить воспроизведение").setItems(options) { _, which -> when(which){0->toast("Остановка после трека");1,2,3,4->startTimer(listOf(5L,15L,30L,60L)[which-1]);5->askMinutes();6->cancelTimer()} }.show() }
    private fun startTimer(min:Long){timerRunnable?.let(mainHandler::removeCallbacks);timerRunnable=Runnable{controller?.pause();toast("Таймер остановил воспроизведение")};mainHandler.postDelayed(timerRunnable!!,min*60000);toast("Таймер: $min минут")}
    private fun askMinutes(){val input=EditText(this).apply{inputType=2;hint="Минуты"};AlertDialog.Builder(this).setTitle("Задать время").setView(input).setPositiveButton("Запустить"){_,_->input.text.toString().toLongOrNull()?.takeIf{it>0}?.let(::startTimer)}.setNegativeButton("Отмена",null).show()}
    private fun cancelTimer(){timerRunnable?.let(mainHandler::removeCallbacks);timerRunnable=null;toast("Таймер выключен")}
    private fun showSound(){val layout=LinearLayout(this).vertical();listOf("Tone","Speed","Preamp").forEach{layout.addView(TextView(this).apply{text=it;setTextColor(fg())});layout.addView(SeekBar(this).apply{max=100;progress=50})};AlertDialog.Builder(this).setTitle("Volume and Sound").setMessage("Эквалайзер").setView(layout).setPositiveButton("Готово",null).show()}
    private fun showAb(){val input=TextView(this).apply{text="A: ${pointA?.let(::formatMs) ?: "—"}\nB: ${pointB?.let(::formatMs) ?: "—"}";setTextColor(fg());setPadding(20,10,20,10)};AlertDialog.Builder(this).setTitle("Точки A–B").setView(input).setNeutralButton("Сбросить"){_,_->pointA=null;pointB=null}.setNegativeButton("Установить A"){_,_->pointA=controller?.currentPosition}.setPositiveButton("Установить B"){_,_->pointB=controller?.currentPosition}.show()}
    private fun showFavorite(){AlertDialog.Builder(this).setTitle("Избранное").setItems(arrayOf("Добавить текущий трек","Открыть плейлист")){_,which->if(which==0&&currentTrack>=0){toast("Добавлено в избранное")}else if(which==1){currentPlaylist=0;showPage(2)}}.show()}
    private fun showSort(){AlertDialog.Builder(this).setTitle("Сортировка").setItems(arrayOf("По имени","По длительности")){_,which->tracks.sortBy{if(which==0)it.title.lowercase() else it.title};refreshFiles()}.show()}
    private fun showMenu(){AlertDialog.Builder(this).setTitle("Меню").setItems(arrayOf("Настройки и плейлисты","Файлы плейлиста","Настройки звука")){_,which->when(which){0->showPage(0);1->showPage(2);2->showSound()}}.show()}
    private fun newPlaylist(draw:()->Unit){val input=EditText(this).apply{hint="Название"};AlertDialog.Builder(this).setTitle("Новый плейлист").setView(input).setPositiveButton("Создать"){_,_->input.text.toString().trim().takeIf{it.isNotEmpty()}?.let{playlists.add(it);draw()}}.setNegativeButton("Отмена",null).show()}

    private fun connectPlayback(){val token=SessionToken(this,ComponentName(this,PlaybackService::class.java));controllerFuture=MediaController.Builder(this,token).buildAsync();controllerFuture.addListener({controller=controllerFuture.get()},ContextCompat.getMainExecutor(this))}
    private fun showPage(n:Int){page=n.coerceIn(0,2);pager.translationX=-page*pager.width.toFloat(); if(page==2)refreshFiles()}
    private fun persistRead(uri:Uri){try{contentResolver.takePersistableUriPermission(uri,IntentFlags.READ)}catch(_:Exception){}}
    private fun queryName(uri:Uri)=contentResolver.query(uri,arrayOf("_display_name"),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}
    private fun pageContainer()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,14,14,24);setBackgroundColor(bg())}
    private fun header(box:LinearLayout,title:String,sub:String,icon:String,onClick:()->Unit){val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val texts=LinearLayout(this).vertical();texts.addView(TextView(this).apply{text=title;textSize=20f;setTextColor(fg())});texts.addView(muted(sub));row.addView(texts,LinearLayout.LayoutParams(0,-2,1f));row.addView(button(icon,onClick));box.addView(row)}
    private fun sectionTitle(s:String)=TextView(this).apply{text=s;setTextColor(Color.rgb(143,167,194));setPadding(2,18,2,8)}
    private fun label(s:String)=TextView(this).apply{text=s;setTextColor(Color.rgb(105,169,255));setPadding(0,8,0,4)}
    private fun muted(s:String)=TextView(this).apply{text=s;textSize=12f;setTextColor(muted())}
    private fun button(s:String,onClick:()->Unit)=Button(this).apply{text=s;setOnClickListener{onClick()};setTextColor(fg());setBackgroundColor(Color.TRANSPARENT)}
    private fun rowButton(title:String,detail:String,onClick:()->Unit)=button("$title\n$detail",onClick).apply{gravity=Gravity.START;setPadding(14,12,8,12);setBackgroundColor(panel())}
    private fun rowWithControl(title:String,control:View,onClick:()->Unit):View{val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};row.addView(TextView(this).apply{text=title;setTextColor(fg())},LinearLayout.LayoutParams(0,-2,1f));row.addView(control);row.setOnClickListener{onClick()};return row}
    private fun switchRow(text:String,on:Boolean)=rowWithControl(text,Switch(this).apply{isChecked=on}){}
    private fun EditText.styled(h:String)=apply{hint=h;setTextColor(fg());setHintTextColor(muted());setSingleLine(true)}
    private fun LinearLayout.vertical()=apply{orientation=LinearLayout.VERTICAL};private fun LinearLayout.horizontal()=apply{orientation=LinearLayout.HORIZONTAL};private fun LinearLayout.card()=apply{setPadding(14,14,14,14);setBackgroundColor(panel())}
    private fun seek(action:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(f)action(p)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}}
    private fun formatMs(ms:Long)=fmt(ms/1000);private fun bg()=if(isLight())Color.rgb(242,245,249) else Color.rgb(7,11,17);private fun panel()=if(isLight())Color.WHITE else Color.rgb(18,25,34);private fun fg()=if(isLight())Color.rgb(23,34,53) else Color.rgb(243,247,255);private fun muted()=if(isLight())Color.rgb(99,116,138) else Color.rgb(156,175,196);private fun isLight()=getPreferences(Context.MODE_PRIVATE).getBoolean("light",false);private fun toggleTheme(v:Button){val light=!isLight();getPreferences(Context.MODE_PRIVATE).edit().putBoolean("light",light).apply();v.text=if(light)"Light Theme" else "Dark Theme";window.statusBarColor=bg();toast("Тема изменена")};private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private inner class SwipeListener:View.OnTouchListener{private var down=0f;override fun onTouch(v:View,e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{down=e.x;return true};MotionEvent.ACTION_UP->{val dx=e.x-down;if(kotlin.math.abs(dx)>70)showPage(page+if(dx<0)1 else -1);return true}};return true}}
    override fun onDestroy(){timerRunnable?.let(mainHandler::removeCallbacks);if(::controllerFuture.isInitialized)MediaController.releaseFuture(controllerFuture);super.onDestroy()}
}

enum class PlayerRepeat { OFF, ALL, ONE }
private object IntentFlags { const val READ=1 }
private fun Track.toMediaItem()=MediaItem.Builder().setMediaId(id).setUri(Uri.parse(uri)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
