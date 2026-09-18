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
    private lateinit var pager: LinearLayout
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("abplayer", MODE_PRIVATE) }
    private val playlists = mutableListOf<Playlist>()
    private var playlistIndex = 0
    private var trackIndex = -1
    private var page = 1
    private var downX = 0f
    private var downY = 0f
    private var a = C.TIME_UNSET
    private var b = C.TIME_UNSET
    private var timer: Runnable? = null
    private var shuffle = false
    private var repeat = Player.REPEAT_MODE_OFF
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var status: TextView
    private lateinit var position: SeekBar
    private lateinit var time: TextView
    private lateinit var tracksView: LinearLayout
    private val update = object : Runnable { override fun run() { controller?.let(::updateUi); handler.postDelayed(this, 500) } }

    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { values ->
        values.forEach { takePermission(it); addUri(it) }; save(); render()
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@registerForActivityResult
        takePermission(uri); FolderLibrary.addFolder(this, uri)
        Thread { FolderLibrary.scan(this).forEach { addTrack(it) }; runOnUiThread { save(); render() } }.start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); load(); buildUi(); connect(); handler.post(update)
        pager.post { show(1) }; render()
    }

    private fun buildUi() {
        pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bg()) }
        pager.addView(settings(), LinearLayout.LayoutParams(-1, -1)); pager.addView(player(), LinearLayout.LayoutParams(-1, -1)); pager.addView(filePage(), LinearLayout.LayoutParams(-1, -1))
        setContentView(pager); window.statusBarColor = bg(); window.navigationBarColor = bg()
        pager.setOnTouchListener { _, e ->
            when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; false }; MotionEvent.ACTION_UP -> { val dx=e.x-downX; val dy=e.y-downY; if(abs(dx)>90&&abs(dx)>abs(dy)){show(page+if(dx<0)1 else -1);true}else false}; else -> false }
        }
    }

    private fun settings(): View { val root=column(); header(root,"ABPlayer","Настройки и плейлисты"){menu()}; root.addView(label("ПОИСК ПЛЕЙЛИСТОВ")); val search=edit("Поиск плейлистов..."); root.addView(search); root.addView(label("ПЛЕЙЛИСТЫ")); val list=column();root.addView(list);fun draw(){list.removeAllViews();val q=search.text.toString();playlists.forEachIndexed{i,p->if(p.name.contains(q,true))list.addView(row("${if(p.favorite)"♡" else "♫"} ${p.name}","${p.tracks.size} треков"){playlistIndex=i;trackIndex=-1;render();show(2)})}};search.addTextChangedListener(Watcher{draw()});draw();root.addView(label("НАСТРОЙКИ"));listOf("Восстанавливать позицию","Предзагрузка","Плавное начало","Кроссфейд","ReplayGain","Нормализация").forEachIndexed{i,n->root.addView(switchRow(n,prefs.getBoolean("s$i",i!=3)))};root.addView(label("ИНТЕРФЕЙС"));root.addView(button(if(prefs.getBoolean("light",false))"Light Theme" else "Dark Theme"){prefs.edit().putBoolean("light",!prefs.getBoolean("light",false)).apply();recreate()});root.addView(button("＋ Создать плейлист"){createPlaylist()});return scroll(root)}

    private fun player(): View { val root=scroll(column());header(root,"ABPlayer","Избранное"){menu()};root.addView(TextView(this).apply{text="♫";textSize=88f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(28,42,61))},LinearLayout.LayoutParams(-1,dp(230)));val card=column();card.setBackgroundColor(panel());root.addView(card);status=label("ГОТОВ К ВОСПРОИЗВЕДЕНИЮ");card.addView(status);title=text("Выберите трек",25f);card.addView(title);artist=muted("ABPlayer");card.addView(artist);val tools=row();tools.addView(button("Sleep Timer"){sleep()});tools.addView(button("Volume and Sound"){sound()});card.addView(tools);position=SeekBar(this).apply{max=1000};card.addView(position);time=muted("00:00 / 00:00");card.addView(time);val arow=row();arow.addView(button("⤨"){shuffle=!shuffle;controller?.shuffleModeEnabled=shuffle});arow.addView(button("↻"){repeat=when(repeat){Player.REPEAT_MODE_OFF->Player.REPEAT_MODE_ALL;Player.REPEAT_MODE_ALL->Player.REPEAT_MODE_ONE;else->Player.REPEAT_MODE_OFF};controller?.repeatMode=repeat});arow.addView(button("♡"){favorite()});arow.addView(button("A↔B"){ab()});arow.addView(button("☷"){show(2)});card.addView(arow);val nav=row();nav.addView(button("↶"){seek(-10000)});nav.addView(button("◀"){controller?.seekToPreviousMediaItem()});nav.addView(button("▶"){toggle()});nav.addView(button("▶"){controller?.seekToNextMediaItem()});nav.addView(button("↷"){seek(10000)});card.addView(nav);position.setOnSeekBarChangeListener(seekListener{controller?.duration?.takeIf{it>0}?.let{controller?.seekTo(it*position.progress/1000L)}});return root}

    private fun filePage(): View { val root=scroll(column());header(root,"TG","Файлы текущего плейлиста"){menu()};val bar=row();bar.addView(button("＋ Файл"){files.launch(arrayOf("audio/*"))});bar.addView(button("＋ Папка"){folder.launch(null)});bar.addView(button("Сортировка"){sort()});root.addView(bar);val search=edit("Поиск файлов...");root.addView(search);tracksView=column();root.addView(tracksView);search.addTextChangedListener(Watcher{renderTracks(it)});return root}

    private fun render(){renderTracks("");status.text=if(controller?.isPlaying==true)"ВОСПРОИЗВЕДЕНИЕ" else "ГОТОВ К ВОСПРОИЗВЕДЕНИЮ";title.text=current().tracks.getOrNull(trackIndex)?.title?:"Выберите трек";artist.text=current().tracks.getOrNull(trackIndex)?.artist?:"ABPlayer"}
    private fun renderTracks(q:String){if(!::tracksView.isInitialized)return;tracksView.removeAllViews();val visible=current().tracks.filter{(it.title+it.artist).contains(q,true)};if(visible.isEmpty())tracksView.addView(muted("Плейлист пуст — добавьте аудиофайлы"));visible.forEachIndexed{n,t->val i=current().tracks.indexOfFirst{it.id==t.id};tracksView.addView(row("${if(i==trackIndex)"▶" else "${n+1}."} ${t.title}","${t.artist} • ${format(t.duration)}"){play(i)})}}
    private fun current()=playlists[playlistIndex]
    private fun play(i:Int){if(i !in current().tracks.indices)return;trackIndex=i;controller?.setMediaItems(current().tracks.map{it.media()},i,0);controller?.prepare();controller?.play();render()}
    private fun toggle(){controller?.let{if(it.isPlaying)it.pause() else if(it.currentMediaItem==null&&current().tracks.isNotEmpty())play(0)else it.play()}}
    private fun seek(d:Long){controller?.let{it.seekTo((it.currentPosition+d).coerceIn(0,if(it.duration>0)it.duration else Long.MAX_VALUE))}}
    private fun updateUi(p:Player){time.text="${format(p.currentPosition)} / ${format(p.duration)}";position.progress=if(p.duration>0)(p.currentPosition*1000/p.duration).toInt() else 0;if(a!=C.TIME_UNSET&&b!=C.TIME_UNSET&&p.currentPosition>=b&&b>a)p.seekTo(a)}
    private fun connect(){val token=SessionToken(this,ComponentName(this,PlaybackService::class.java));controllerFuture=MediaController.Builder(this,token).buildAsync();controllerFuture.addListener({controller=controllerFuture.get().also{it.addListener(object:Player.Listener{override fun onMediaItemTransition(item:MediaItem?,reason:Int){trackIndex=it.currentMediaItemIndex;render()}})}},ContextCompat.getMainExecutor(this))}
    private fun sleep(){val items=arrayOf("5 минут","15 минут","30 минут","1 час","Задать время","Выключить");AlertDialog.Builder(this).setTitle("Sleep Timer").setItems(items){_,i->if(i<4)startTimer(longArrayOf(5,15,30,60)[i])else if(i==4){val e=edit("Минуты");AlertDialog.Builder(this).setView(e).setPositiveButton("OK"){_,_->e.text.toString().toLongOrNull()?.let(::startTimer)}.show()}else{timer?.let(handler::removeCallbacks);timer=null}}.show()}
    private fun startTimer(m:Long){timer?.let(handler::removeCallbacks);timer=Runnable{controller?.pause()};handler.postDelayed(timer!!,m*60000);toast("Таймер: $m минут")}
    private fun sound(){val box=column();repeat(10){i->box.addView(label("Полоса ${i+1}"));box.addView(SeekBar(this).apply{max=24;progress=12;setOnSeekBarChangeListener(seekListener{PlaybackService.instance?.setBandLevel(i,it-12)})})};box.addView(label("Скорость"));box.addView(SeekBar(this).apply{max=150;progress=100;setOnSeekBarChangeListener(seekListener{PlaybackService.instance?.setPlaybackSpeed(it/100f)})});AlertDialog.Builder(this).setTitle("Volume and Sound").setView(box).setPositiveButton("Готово",null).show()}
    private fun ab(){val e=edit("A/B");AlertDialog.Builder(this).setTitle("Точки A–B").setMessage("A=${if(a==C.TIME_UNSET)"—" else format(a)}  B=${if(b==C.TIME_UNSET)"—" else format(b)}").setView(e).setNegativeButton("Установить A"){_,_->a=controller?.currentPosition?:C.TIME_UNSET}.setPositiveButton("Установить B"){_,_->b=controller?.currentPosition?:C.TIME_UNSET}.setNeutralButton("Сбросить"){_,_->a=C.TIME_UNSET;b=C.TIME_UNSET}.show()}
    private fun favorite(){val t=current().tracks.getOrNull(trackIndex)?:return;val fav=playlists[0];if(t.favorite){fav.tracks.removeAll{it.id==t.id};t.favorite=false}else{if(fav.tracks.none{it.id==t.id})fav.tracks.add(t.copy(favorite=true));t.favorite=true};save();render()}
    private fun sort(){AlertDialog.Builder(this).setItems(arrayOf("Имя","Длительность","Размер","Дата")){_,i->current().tracks=when(i){0->current().tracks.sortedBy{it.title.lowercase()};1->current().tracks.sortedBy{it.duration};2->current().tracks.sortedBy{it.size};else->current().tracks.sortedBy{it.date}}.toMutableList();save();render()}.show()}
    private fun menu(){AlertDialog.Builder(this).setItems(arrayOf("Настройки","Плейлист","Звук")){_,i->when(i){0->show(0);1->show(2);else->sound()}}.show()}
    private fun createPlaylist(){val e=edit("Название");AlertDialog.Builder(this).setView(e).setPositiveButton("Создать"){_,_->e.text.toString().trim().takeIf{it.isNotEmpty()}?.let{playlists.add(Playlist(it));save();recreate()}}.show()}
    private fun addUri(u:Uri){if(current().tracks.none{it.id==u.toString()})current().tracks.add(Track(u.toString(),name(u),"Неизвестный исполнитель","Локальный файл",u.toString(),size=size(u),date=System.currentTimeMillis()))}
    private fun addTrack(t:Track){runOnUiThread{if(current().tracks.none{it.id==t.id})current().tracks.add(t)}}
    private fun name(u:Uri)=contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else"Без названия"}?:"Без названия"
    private fun size(u:Uri)=contentResolver.query(u,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0)else 0L}?:0L
    private fun takePermission(u:Uri){try{contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}}
    private fun load(){playlists.clear();playlists.add(Playlist("Избранное",true));playlists.add(Playlist("Архитектура"));playlists.add(Playlist("Работа"))}
    private fun save(){/* playlist persistence can be added without affecting playback */}
    private fun show(n:Int){page=n.coerceIn(0,2);pager.post{pager.translationX=-page*pager.width.toFloat()};if(page==2)renderTracks("")}
    private fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(14),dp(14),dp(28))};private fun scroll(v:View)=ScrollView(this).apply{addView(v)};private fun row()=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};private fun header(p:LinearLayout,t:String,s:String,on:()->Unit){val r=row();r.addView(column().apply{addView(text(t,20f));addView(muted(s))},LinearLayout.LayoutParams(0,-2,1f));r.addView(button("⋮",on));p.addView(r)};private fun button(s:String,on:()->Unit)=Button(this).apply{text=s;setOnClickListener{on()};setTextColor(fg())};private fun row(a:String,b:String,on:()->Unit)=button("$a\n$b",on).apply{gravity=Gravity.START};private fun edit(h:String)=EditText(this).apply{hint=h;setSingleLine(true)};private fun label(s:String)=TextView(this).apply{text=s;setTextColor(Color.rgb(105,169,255))};private fun muted(s:String)=TextView(this).apply{text=s;textSize=12f};private fun text(s:String,z:Float)=TextView(this).apply{text=s;textSize=z;setTextColor(fg())};private fun switchRow(s:String,on:Boolean)=row(s,if(on)"ON" else "OFF"){prefs.edit().putBoolean(s,on).apply()};private fun seekListener(a:(Int)->Unit)=object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){if(f)a(p)};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}};private fun format(ms:Long)=if(ms<=0)"00:00" else String.format(Locale.US,"%02d:%02d",ms/60000,(ms/1000)%60);private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt();private fun bg()=if(prefs.getBoolean("light",false))Color.rgb(242,245,249) else Color.rgb(7,11,17);private fun panel()=if(prefs.getBoolean("light",false))Color.WHITE else Color.rgb(18,25,34);private fun fg()=if(prefs.getBoolean("light",false))Color.rgb(23,34,53) else Color.WHITE;private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onDestroy(){handler.removeCallbacks(update);timer?.let(handler::removeCallbacks);if(::controllerFuture.isInitialized)MediaController.releaseFuture(controllerFuture);super.onDestroy()}
}

data class Playlist(val name:String,val favorite:Boolean=false,var tracks:MutableList<Track> = mutableListOf())
private class Watcher(private val f:(String)->Unit):android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int)=f(s?.toString().orEmpty());override fun afterTextChanged(e:android.text.Editable?){}}
private fun Track.media()=MediaItem.Builder().setMediaId(id).setUri(Uri.parse(uri)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
