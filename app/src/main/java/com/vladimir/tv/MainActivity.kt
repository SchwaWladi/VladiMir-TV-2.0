package com.vladimir.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.content.res.Configuration
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    data class Entry(val title:String,val url:String,val group:String,val logo:String="",val type:String="tv")
    private val prefs by lazy { getSharedPreferences("vladimir2",MODE_PRIVATE) }
    private val items = mutableListOf<Entry>()
    private val lists = mutableListOf<Pair<String,String>>()
    private var current = "home"
    private var lang = "ru"
    private lateinit var base:LinearLayout
    private lateinit var content:LinearLayout
    private var player:ExoPlayer? = null
    private var playerView:PlayerView? = null
    private var activeList = -1
    private var selectedGroup = ""
    private var remoteServer: ServerSocket? = null
    private val remoteToken = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var remoteRunning = false
    private fun isPhone():Boolean = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_TELEVISION && resources.displayMetrics.widthPixels / resources.displayMetrics.density < 700
    private val bg = Color.rgb(12,17,29)
    private val panel = Color.rgb(28,36,53)
    private val accent = Color.rgb(229,35,66)
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun tile(title:String,subtitle:String,action:()->Unit):View {
        val outer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;isFocusable=true;isClickable=true;setPadding(dp(16),dp(18),dp(16),dp(18));background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(48,58,83),Color.rgb(20,26,42))).apply{cornerRadius=dp(18).toFloat();setStroke(dp(1),Color.rgb(80,87,105))};setOnClickListener{action()}}
        outer.addView(label(title,22f,true))
        outer.addView(label(subtitle,13f))
        return outer
    }

    private fun tr(ru:String,de:String,en:String)= when(lang){"de"->de;"en"->en;else->ru}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isPhone()) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        lang=prefs.getString("language","ru")?:"ru"
        val stored=prefs.getString("playlists","[]")?:"[]"
        try { val arr=JSONArray(stored); for(i in 0 until arr.length()){val o=arr.getJSONObject(i);lists.add(o.optString("name") to o.optString("url"))} }catch(_:Exception){}
        activeList=prefs.getInt("active",if(lists.isEmpty()) -1 else 0)
        player=ExoPlayer.Builder(this).build()
        startRemoteServer()
        showHome()
        if(activeList in lists.indices) loadPlaylist(activeList)
    }
    private fun saveLists(){val a=JSONArray();lists.forEach{a.put(JSONObject().put("name",it.first).put("url",it.second))};prefs.edit().putString("playlists",a.toString()).putInt("active",activeList).apply()}
    private fun background(color:Int,r:Float=15f)=GradientDrawable().apply{setColor(color);cornerRadius=r}
    private fun label(s:String,size:Float=19f,bold:Boolean=false)=TextView(this).apply{text=s;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;setPadding(14,10,14,10);if(bold) setTypeface(null,Typeface.BOLD)}
    private fun button(s:String,click:()->Unit)=Button(this).apply{text=s;textSize=16f;isAllCaps=false;setTextColor(Color.WHITE);background=background(panel);setOnClickListener{click()};isFocusable=true;setPadding(10,8,10,8)}
    private fun sectionTitle(s:String)=label(s,25f,true).also{content.addView(it)}
    private fun scaffold(page:String){
        current=page
        base=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg)}
        setContentView(base)
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(15,12,15,12)}
        header.addView(label("VladiMir TV  2.0",26f,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("⚙") {showSettings()},LinearLayout.LayoutParams(85,60))
        base.addView(header)
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(10,0,10,10)}
        val menu=listOf(Triple("home",tr("Главная","Start","Home"),{showHome()}),Triple("tv",tr("ТВ","Fernsehen","Live TV"),{showCatalog("tv")}),Triple("movies",tr("Фильмы","Filme","Movies"),{showCatalog("movies")}),Triple("series",tr("Сериалы","Serien","Series"),{showCatalog("series")}),Triple("favorites",tr("Избранное","Favoriten","Favorites"),{showCatalog("favorites")}),Triple("playlists",tr("Плейлисты","Playlisten","Playlists"),{showPlaylists()}))
        menu.forEach{(id,name,action)->val b=button(name,action);b.background=background(if(id==page)accent else panel);val lp=LinearLayout.LayoutParams(dp(if(isPhone()) 132 else 170),dp(if(isPhone()) 52 else 62));lp.setMargins(dp(4),0,dp(4),0);nav.addView(b,lp)}
        val navScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false};navScroll.addView(nav);base.addView(navScroll)
        val scroll=ScrollView(this).apply{fillViewport=true}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(25,15,25,25)}
        scroll.addView(content);base.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun showHome(){
        scaffold("home")
        val hero=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(26),dp(22),dp(26));background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(83,20,45),Color.rgb(21,28,48))).apply{cornerRadius=dp(18).toFloat()}}
        hero.addView(label("VLADIMIR  TV",32f,true))
        hero.addView(label(tr("Твой домашний кинотеатр", "Dein Heimkino", "Your home cinema"),23f,true))
        hero.addView(label(tr("Телеканалы • Фильмы • Сериалы", "Fernsehen • Filme • Serien", "Live TV • Movies • Series"),16f))
        hero.addView(button(tr("▶  Смотреть ТВ", "▶  Fernsehen", "▶  Watch TV")){showCatalog("tv")})
        content.addView(hero)
        sectionTitle(tr("Что будем смотреть?", "Was möchtest du sehen?", "What would you like to watch?"))
        val sections=listOf(
          Triple(tr("ТЕЛЕКАНАЛЫ","FERNSEHEN","LIVE TV"),tr("Категории и избранное","Kategorien und Favoriten","Categories and favorites"),"tv"),
          Triple(tr("ФИЛЬМЫ","FILME","MOVIES"),tr("Каталог провайдера","Anbieterkatalog","Provider catalog"),"movies"),
          Triple(tr("СЕРИАЛЫ","SERIEN","SERIES"),tr("Сезоны при наличии данных","Staffeln, falls verfügbar","Seasons when available"),"series"),
          Triple(tr("ИЗБРАННОЕ","FAVORITEN","FAVORITES"),tr("Любимые каналы и видео","Deine Favoriten","Your favorites"),"favorites"),
          Triple(tr("ПЛЕЙЛИСТЫ","PLAYLISTEN","PLAYLISTS"),tr("Добавить или изменить M3U","M3U hinzufügen oder ändern","Add or edit M3U"),"playlists"),
          Triple(tr("ТЕЛЕФОН / QR","HANDY / QR","PHONE / QR"),tr("Поиск и управление с телефона","Suche und Steuerung per Handy","Search and control from phone"),"remote")
        )
        if(isPhone()){
            sections.forEach{(name,sub,id)->val v=tile(name,sub){openSection(id)};content.addView(v,LinearLayout.LayoutParams(-1,dp(116)).apply{setMargins(0,dp(5),0,dp(5))})}
        }else{
            sections.chunked(3).forEach{group->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};group.forEach{(name,sub,id)->row.addView(tile(name,sub){openSection(id)},LinearLayout.LayoutParams(0,dp(150),1f).apply{setMargins(dp(5),dp(5),dp(5),dp(5))})};content.addView(row)}
        }
        val status=if(lists.isEmpty())tr("Добавь первый M3U-плейлист", "Erste M3U-Playlist hinzufügen", "Add your first M3U playlist") else "${lists.getOrNull(activeList)?.first?:"—"}  •  ${items.size}"
        content.addView(label(status,17f))
        val recent=prefs.getString("recent","")?:"";items.find{it.url==recent}?.let{e->content.addView(button(tr("Продолжить: ","Fortsetzen: ","Continue: ")+e.title){play(e)})}
    }
    private fun openSection(id:String){when(id){"playlists"->showPlaylists();"remote"->showRemote();else->showCatalog(id)}}
    private fun showCatalog(type:String){scaffold(type);sectionTitle(when(type){"tv"->tr("Телеканалы","Fernsehen","Live TV");"movies"->tr("Фильмы","Filme","Movies");"series"->tr("Сериалы","Serien","Series");else->tr("Избранное","Favoriten","Favorites")})
        if(items.isEmpty()){content.addView(label(tr("Сначала добавь M3U-плейлист","Bitte zuerst eine M3U-Playlist hinzufügen","Please add an M3U playlist first")));content.addView(button("+ M3U"){showPlaylists()});return}
        val fav=prefs.getStringSet("favorites",emptySet())?:emptySet()
        val candidates=items.toList().filter{when(type){"favorites"->fav.contains(it.url);"tv"->it.type=="tv";else->it.type==type}}
        if(candidates.isEmpty()){content.addView(label(tr("В этом разделе пока нет данных от плейлиста","Keine passenden Einträge in der Playlist","No matching entries in this playlist")));return}
        val groups=candidates.map{it.group}.distinct().sorted()
        val filter=Spinner(this);val options=listOf(tr("Все категории","Alle Kategorien","All categories"))+groups
        filter.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,options)
        content.addView(filter)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};content.addView(list)
        fun fill(group:String){list.removeAllViews();candidates.filter{group.isEmpty()||it.group==group}.take(1500).forEach{e->val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,2,0,2)};line.addView(button("▶  ${e.title}"){play(e)},LinearLayout.LayoutParams(0,58,1f));line.addView(button(if(fav.contains(e.url))"★" else "☆"){toggleFav(e);showCatalog(type)},LinearLayout.LayoutParams(65,58));list.addView(line)}}
        filter.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?){};override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,position:Int,id:Long){fill(if(position==0)"" else groups[position-1])}}
    }
    private fun toggleFav(e:Entry){val s=(prefs.getStringSet("favorites",emptySet())?:emptySet()).toMutableSet();if(!s.add(e.url))s.remove(e.url);prefs.edit().putStringSet("favorites",s).apply()}
    private fun showPlaylists(){scaffold("playlists");sectionTitle(tr("Управление плейлистами","Playlisten verwalten","Manage playlists"));content.addView(button("+  ${tr("Добавить M3U","M3U hinzufügen","Add M3U")}"){editList(-1)});lists.forEachIndexed{i,p->val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,7,0,7)};row.addView(button((if(i==activeList)"✓  " else "")+p.first){activeList=i;saveLists();loadPlaylist(i);showHome()},LinearLayout.LayoutParams(0,60,1f));row.addView(button("✎"){editList(i)},LinearLayout.LayoutParams(75,60));row.addView(button("×"){AlertDialog.Builder(this).setMessage(tr("Удалить плейлист?","Playlist löschen?","Delete playlist?")).setPositiveButton("OK"){_,_->lists.removeAt(i);activeList=if(lists.isEmpty())-1 else 0;saveLists();if(activeList>=0)loadPlaylist(activeList) else items.clear();showPlaylists()}.setNegativeButton(tr("Отмена","Abbrechen","Cancel"),null).show()},LinearLayout.LayoutParams(75,60));content.addView(row)}
        content.addView(label(tr("Плейлисты сохраняются на устройстве. Добавлять ссылки можно и с телефона через QR в одной сети.","Playlisten werden lokal gespeichert. Links lassen sich per QR im selben WLAN hinzufügen.","Playlists are stored locally. Add links from your phone via QR on the same Wi-Fi."),15f))
    }
    private fun editList(index:Int){val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,5,20,0)};val name=EditText(this).apply{hint=tr("Название","Name","Name");setSingleLine(true);setText(lists.getOrNull(index)?.first?:"")};val url=EditText(this).apply{hint="https://...m3u";setSingleLine(true);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;setText(lists.getOrNull(index)?.second?:"")};form.addView(name);form.addView(url)
        AlertDialog.Builder(this).setTitle(tr("Плейлист M3U","M3U-Playlist","M3U playlist")).setView(form).setPositiveButton(tr("Сохранить","Speichern","Save")){_,_->val u=url.text.toString().trim();if(!u.startsWith("https://")&&!u.startsWith("http://")){toast("HTTP(S) URL required");return@setPositiveButton};val n=name.text.toString().trim().ifEmpty{"Playlist ${lists.size+1}"};if(index>=0)lists[index]=n to u else lists.add(n to u);activeList=if(index>=0)index else lists.lastIndex;saveLists();loadPlaylist(activeList);showPlaylists()}.setNegativeButton(tr("Отмена","Abbrechen","Cancel"),null).show()
    }
    private fun loadPlaylist(index:Int){val u=lists.getOrNull(index)?.second?:return;thread{try{val c=URL(u).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=30000;c.instanceFollowRedirects=true;c.setRequestProperty("User-Agent","VladiMirTV/2.0");val body=c.inputStream.bufferedReader().use{it.readText()};c.disconnect();val parsed=parseM3u(body);runOnUiThread{if(index==activeList){items.clear();items.addAll(parsed);toast("${parsed.size} items");if(current=="home")showHome()}}}catch(e:Exception){runOnUiThread{toast("M3U: ${e.message}")}}}}
    private fun parseM3u(raw:String):List<Entry>{val out=ArrayList<Entry>();var title="";var group="";var logo="";val attr=Regex("([\\w-]+)=\"([^\"]*)\"");for(line in raw.lineSequence().map{it.trim()}){if(line.startsWith("#EXTINF",true)){val a=attr.findAll(line).associate{it.groupValues[1] to it.groupValues[2]};title=line.substringAfterLast(',',a["tvg-name"]?:"Stream").trim();group=a["group-title"]?:"Other";logo=a["tvg-logo"]?:""}else if(line.startsWith("http://")||line.startsWith("https://")){val g=group.lowercase();val type=when{listOf("series","сериал","serie","show").any{g.contains(it)}->"series";listOf("movie","film","фильм","кино","vod").any{g.contains(it)}->"movies";else->"tv"};out.add(Entry(title.ifBlank{"Stream"},line,group.ifBlank{"Other"},logo,type));title="";group="";logo=""}};return out}
    private fun play(e:Entry){prefs.edit().putString("recent",e.url).apply();scaffold("player");sectionTitle(e.title);val pv=PlayerView(this).apply{useController=true;isFocusable=true;requestFocus()};playerView=pv;content.addView(pv,LinearLayout.LayoutParams(-1,if(isPhone()) (resources.displayMetrics.widthPixels*9/16) else 460));pv.player=player;try{player?.setMediaItem(MediaItem.fromUri(Uri.parse(e.url)));player?.prepare();player?.play()}catch(ex:Exception){toast(ex.message?:"Playback error")};content.addView(button(tr("Назад","Zurück","Back")){player?.pause();showCatalog(e.type)})}
    private fun showSettings(){scaffold("settings");sectionTitle(tr("Настройки","Einstellungen","Settings"));content.addView(label(tr("Язык интерфейса","Sprache","Interface language")));val choices=arrayOf("Русский","Deutsch","English");val codes=arrayOf("ru","de","en");choices.forEachIndexed{i,s->content.addView(button((if(lang==codes[i])"✓  " else "")+s){lang=codes[i];prefs.edit().putString("language",lang).apply();showSettings()})};sectionTitle(tr("Информация","Information","Information"));content.addView(label(tr("Версия 2.0. Дорожки звука и субтитры выбираются в панели плеера, если поток их содержит.","Version 2.0. Tonspuren und Untertitel sind im Player verfügbar, sofern der Stream sie enthält.","Version 2.0. Audio tracks and subtitles are available in player controls when provided by the stream."),16f));content.addView(label(tr("Облачное управление и QR-привязка пока не подключены.","Cloud-Steuerung und QR-Kopplung sind noch nicht eingerichtet.","Cloud management and QR pairing are not configured yet."),16f))}
    private fun localIp(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var ip: String? = null
        while (interfaces.hasMoreElements() && ip == null) {
            val net = interfaces.nextElement()
            if (!net.isLoopback && net.isUp) {
                val addresses = net.inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) { ip = a.hostAddress; break }
                }
            }
        }
        ip
    } catch (_: Exception) { null }
    private fun remoteUrl(): String? = localIp()?.let { "http://$it:8765/?key=$remoteToken" }
    private fun showRemote() {
        scaffold("remote")
        sectionTitle(tr("Управление с телефона", "Steuerung mit Handy", "Phone control"))
        val url = remoteUrl()
        if (url == null || !remoteRunning) {
            content.addView(label(tr("Подключи телевизор и телефон к одному Wi-Fi", "TV und Handy mit demselben WLAN verbinden", "Connect TV and phone to the same Wi-Fi")))
            return
        }
        content.addView(label(tr("Сканируй QR-код телефоном в той же Wi-Fi сети", "QR-Code mit Handy im selben WLAN scannen", "Scan QR with phone on the same Wi-Fi")))
        try {
            val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 420, 420)
            val bitmap = Bitmap.createBitmap(420,420,Bitmap.Config.ARGB_8888)
            for (y in 0 until 420) for (x in 0 until 420) bitmap.setPixel(x,y,if(matrix.get(x,y)) Color.BLACK else Color.WHITE)
            val image = ImageView(this); image.setImageBitmap(bitmap)
            content.addView(image,LinearLayout.LayoutParams(-1,460))
        } catch (e:Exception) { content.addView(label("QR error: ${e.message}")) }
        content.addView(label(url,14f))
        content.addView(label(tr("Это локальное управление, не через интернет. Не показывай QR посторонним.", "Nur im lokalen WLAN. QR-Code nicht weitergeben.", "Local Wi-Fi only. Do not share the QR code."),15f))
    }
    private fun startRemoteServer() {
        if (remoteRunning) return
        thread(name="VladiMirRemote") {
            try {
                val server=ServerSocket(8765)
                remoteServer=server;remoteRunning=true
                while (!server.isClosed) {
                    val client=server.accept()
                    thread { handleRemote(client) }
                }
            } catch (_:Exception) { remoteRunning=false }
        }
    }
    private fun escapeHtml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun handleRemote(socket:Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout=5000
                val input=sock.getInputStream().bufferedReader()
                val first=input.readLine()?:return
                val request=first.split(' ')
                if (request.size<2) return
                val uri=Uri.parse(request[1]); val path=uri.path?:"/"
                val key=uri.getQueryParameter("key")
                var body=""
                var status="200 OK"
                if (key!=remoteToken) { status="403 Forbidden";body="Forbidden" }
                else if (path=="/play") {
                    val idx=uri.getQueryParameter("id")?.toIntOrNull()
                    if (idx!=null) runOnUiThread { items.getOrNull(idx)?.let { play(it) } }
                    body="<p>Sent to TV</p><a href='/?key=$remoteToken'>Back</a>"
                } else if (path=="/add") {
                    val name=uri.getQueryParameter("name")?.take(100)?.trim().orEmpty()
                    val url=uri.getQueryParameter("url")?.take(4096)?.trim().orEmpty()
                    if (!url.startsWith("https://") && !url.startsWith("http://")) {status="400 Bad Request";body="Invalid URL"}
                    else {
                        runOnUiThread {
                            lists.add((name.ifBlank{"Playlist ${lists.size+1}"}) to url)
                            activeList=lists.lastIndex;saveLists();loadPlaylist(activeList)
                        }
                        body="<meta charset='utf-8'><p>Playlist added / Плейлист добавлен</p><a href='/?key=$remoteToken'>Back</a>"
                    }
                } else if (path=="/search" || path=="/") {
                    val q=uri.getQueryParameter("q")?:""
                    val matches=items.toList().withIndex().filter { q.isBlank() || it.value.title.contains(q,true) || it.value.group.contains(q,true) }.take(80)
                    val links=matches.joinToString("<hr>") { (idx,e) -> "<a href='/play?key=$remoteToken&id=$idx'>▶ ${escapeHtml(e.title)}</a> <small>${escapeHtml(e.group)}</small>" }
                    body="""<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>VladiMir-TV Remote</title><style>body{background:#0c111d;color:white;font:18px sans-serif;padding:18px}input,button{font:20px sans-serif;padding:12px;max-width:95%}a{color:#9ac6ff;display:inline-block;padding:8px}</style></head><body><h2>VladiMir-TV</h2><p>Поиск / Suche / Search</p><form action="/search"><input type="hidden" name="key" value="$remoteToken"><input name="q" value="${escapeHtml(q)}" placeholder="Канал / Film / Channel" autofocus><button>🔍</button></form><p>${matches.size} результатов / Ergebnisse / results</p>$links<hr><h3>Добавить M3U / M3U hinzufügen / Add M3U</h3><form action="/add"><input type="hidden" name="key" value="$remoteToken"><input name="name" placeholder="Название / Name"><input name="url" type="url" placeholder="https://...m3u" required><button>Добавить / Add</button></form></body></html>"""
                } else { status="404 Not Found";body="Not found" }
                val bytes=body.toByteArray(Charsets.UTF_8)
                val headers="HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write(headers.toByteArray(Charsets.UTF_8));sock.getOutputStream().write(bytes);sock.getOutputStream().flush()
            } catch (_:Exception) {}
        }
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onBackPressed(){if(current=="player"){player?.pause();showHome()}else showHome()}
    override fun onStop(){super.onStop();player?.pause()}
    override fun onDestroy(){remoteServer?.close();remoteRunning=false;playerView?.player=null;player?.release();player=null;super.onDestroy()}
}
