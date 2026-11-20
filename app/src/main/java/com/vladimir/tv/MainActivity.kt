
package com.vladimir.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.content.res.Configuration
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class Entry(
        val title: String,
        val url: String,
        val group: String,
        val logo: String = "",
        val type: String = "tv"
    )

    private val prefs by lazy {
        getSharedPreferences("vladimir2", MODE_PRIVATE)
    }

    private val items = mutableListOf<Entry>()
    private val lists = mutableListOf<Pair<String, String>>()

    private var current = "home"
    private var lang = "ru"
    private var activeList = -1

    private lateinit var base: LinearLayout
    private lateinit var content: LinearLayout

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var remoteServer: ServerSocket? = null
    private val remoteToken = UUID.randomUUID()
        .toString().replace("-", "")

    @Volatile
    private var remoteRunning = false

    private val bg = Color.rgb(12, 17, 29)
    private val panel = Color.rgb(28, 36, 53)
    private val accent = Color.rgb(229, 35, 66)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isPhone(): Boolean {
        val mode = resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        val width = resources.displayMetrics.widthPixels /
            resources.displayMetrics.density
        return mode != Configuration.UI_MODE_TYPE_TELEVISION &&
            width < 700
    }

    private fun tr(ru: String, de: String, en: String): String =
        when (lang) {
            "de" -> de
            "en" -> en
            else -> ru
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isPhone()) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        lang = prefs.getString("language", "ru") ?: "ru"

        try {
            val stored = prefs.getString("playlists", "[]") ?: "[]"
            val array = JSONArray(stored)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                lists.add(
                    obj.optString("name") to obj.optString("url")
                )
            }
        } catch (_: Exception) {
        }

        activeList = prefs.getInt(
            "active",
            if (lists.isEmpty()) -1 else 0
        )

        player = ExoPlayer.Builder(this).build()

        startRemoteServer()
        showHome()

        if (activeList in lists.indices) {
            loadPlaylist(activeList)
        }
    }

    private fun saveLists() {
        val array = JSONArray()

        lists.forEach {
            array.put(
                JSONObject()
                    .put("name", it.first)
                    .put("url", it.second)
            )
        }

        prefs.edit()
            .putString("playlists", array.toString())
            .putInt("active", activeList)
            .apply()
    }

    private fun background(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

    private fun label(
        value: String,
        size: Float = 19f,
        bold: Boolean = false
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }

    private fun button(
        value: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = background(panel)
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun sectionTitle(value: String) {
        content.addView(label(value, 25f, true))
    }

    private fun tile(
        title: String,
        subtitle: String,
        action: () -> Unit
    ): View {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(18), dp(16), dp(18))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(48, 58, 83),
                    Color.rgb(20, 26, 42)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }

            setOnClickListener { action() }
        }

        view.addView(label(title, 22f, true))
        view.addView(label(subtitle, 13f))
        return view
    }

    private fun scaffold(page: String) {
        current = page

        base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        setContentView(base)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        header.addView(
            label("VladiMir TV 2.0", 26f, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            button("⚙") { showSettings() }
        )

        base.addView(header)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val menu = listOf(
            Triple(
                "home",
                tr("Главная", "Start", "Home"),
                { showHome() }
            ),
            Triple(
                "tv",
                tr("ТВ", "Fernsehen", "Live TV"),
                { showCatalog("tv") }
            ),
            Triple(
                "movies",
                tr("Фильмы", "Filme", "Movies"),
                { showCatalog("movies") }
            ),
            Triple(
                "series",
                tr("Сериалы", "Serien", "Series"),
                { showCatalog("series") }
            ),
            Triple(
                "favorites",
                tr("Избранное", "Favoriten", "Favorites"),
                { showCatalog("favorites") }
            ),
            Triple(
                "playlists",
                tr("Плейлисты", "Playlisten", "Playlists"),
                { showPlaylists() }
            )
        )

        menu.forEach { (id, name, action) ->
            val b = button(name, action)
            b.background = background(
                if (id == page) accent else panel
            )

            nav.addView(
                b,
                LinearLayout.LayoutParams(
                    dp(if (isPhone()) 132 else 170),
                    dp(55)
                )
            )
        }

        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        navScroll.addView(nav)
        base.addView(navScroll)

        val scroll = ScrollView(this).apply {
            fillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }

        scroll.addView(content)

        base.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun showHome() {
        scaffold("home")

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(83, 20, 45),
                    Color.rgb(21, 28, 48)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }
        }

        hero.addView(label("VLADIMIR TV", 32f, true))

        hero.addView(
            label(
                tr(
                    "Твой домашний кинотеатр",
                    "Dein Heimkino",
                    "Your home cinema"
                ),
                23f,
                true
            )
        )

        hero.addView(
            button(
                tr(
                    "▶ Смотреть ТВ",
                    "▶ Fernsehen",
                    "▶ Watch TV"
                )
            ) {
                showCatalog("tv")
            }
        )

        content.addView(hero)

        sectionTitle(
            tr(
                "Что будем смотреть?",
                "Was möchtest du sehen?",
                "What would you like to watch?"
            )
        )

        val sections = listOf(
            Triple(
                tr("ТЕЛЕКАНАЛЫ", "FERNSEHEN", "LIVE TV"),
                tr("Все каналы", "Alle Sender", "All channels"),
                "tv"
            ),
            Triple(
                tr("ФИЛЬМЫ", "FILME", "MOVIES"),
                tr("Видеотека", "Mediathek", "Video library"),
                "movies"
            ),
            Triple(
                tr("СЕРИАЛЫ", "SERIEN", "SERIES"),
                tr("Сериалы", "Serien", "TV series"),
                "series"
            ),
            Triple(
                tr("ИЗБРАННОЕ", "FAVORITEN", "FAVORITES"),
                tr("Любимые", "Favoriten", "Favorites"),
                "favorites"
            ),
            Triple(
                tr("ПЛЕЙЛИСТЫ", "PLAYLISTEN", "PLAYLISTS"),
                tr("Управление M3U", "M3U verwalten", "Manage M3U"),
                "playlists"
            ),
            Triple(
                tr("ТЕЛЕФОН / QR", "HANDY / QR", "PHONE / QR"),
                tr("Управление", "Steuerung", "Remote control"),
                "remote"
            )
        )

        if (isPhone()) {
            sections.forEach { (name, sub, id) ->
                content.addView(
                    tile(name, sub) { openSection(id) },
                    LinearLayout.LayoutParams(-1, dp(116))
                )
            }
        } else {
            sections.chunked(3).forEach { group ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                group.forEach { (name, sub, id) ->
                    row.addView(
                        tile(name, sub) { openSection(id) },
                        LinearLayout.LayoutParams(
                            0,
                            dp(150),
                            1f
                        )
                    )
                }

                content.addView(row)
            }
        }

        if (lists.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Добавь первый M3U-плейлист",
                        "Erste M3U-Playlist hinzufügen",
                        "Add your first M3U playlist"
                    )
                )
            )
        } else {
            content.addView(
                label("${items.size} каналов и видео")
            )
        }
    }

    private fun openSection(id: String) {
        when (id) {
            "playlists" -> showPlaylists()
            "remote" -> showRemote()
            else -> showCatalog(id)
        }
    }

    private fun showCatalog(type: String) {
        scaffold(type)

        sectionTitle(
            when (type) {
                "tv" -> tr("Телеканалы", "Fernsehen", "Live TV")
                "movies" -> tr("Фильмы", "Filme", "Movies")
                "series" -> tr("Сериалы", "Serien", "Series")
                else -> tr("Избранное", "Favoriten", "Favorites")
            }
        )

        if (items.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Сначала добавь M3U-плейлист",
                        "Bitte M3U-Playlist hinzufügen",
                        "Please add an M3U playlist"
                    )
                )
            )

            content.addView(
                button("+ M3U") { showPlaylists() }
            )
            return
        }

        val favorites =
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()

        val candidates = items.filter {
            when (type) {
                "favorites" -> favorites.contains(it.url)
                "tv" -> it.type == "tv"
                else -> it.type == type
            }
        }

        if (candidates.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Нет данных",
                        "Keine Einträge",
                        "No entries"
                    )
                )
            )
            return
        }

        val groups = candidates.map { it.group }
            .distinct()
            .sorted()

        val options = listOf(
            tr(
                "Все категории",
                "Alle Kategorien",
                "All categories"
            )
        ) + groups

        val filter = Spinner(this)

        filter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        content.addView(filter)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(list)

        fun fill(group: String) {
            list.removeAllViews()

            candidates
                .filter {
                    group.isEmpty() || it.group == group
                }
                .take(1500)
                .forEach { entry ->

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }

                    row.addView(
                        button("▶ ${entry.title}") {
                            play(entry)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            dp(58),
                            1f
                        )
                    )

                    row.addView(
                        button(
                            if (favorites.contains(entry.url))
                                "★" else "☆"
                        ) {
                            toggleFav(entry)
                            showCatalog(type)
                        }
                    )

                    list.addView(row)
                }
        }

        filter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    fill(
                        if (position == 0) ""
                        else groups[position - 1]
                    )
                }
            }
    }

    private fun toggleFav(entry: Entry) {
        val set = (
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()
        ).toMutableSet()

        if (!set.add(entry.url)) {
            set.remove(entry.url)
        }

        prefs.edit()
            .putStringSet("favorites", set)
            .apply()
    }

    private fun showPlaylists() {
        scaffold("playlists")

        sectionTitle(
            tr(
                "Управление плейлистами",
                "Playlisten verwalten",
                "Manage playlists"
            )
        )

        content.addView(
            button(
                tr(
                    "+ Добавить M3U",
                    "+ M3U hinzufügen",
                    "+ Add M3U"
                )
            ) {
                editList(-1)
            }
        )

        lists.toList().forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(
                button(
                    (if (index == activeList) "✓ " else "") +
                        pair.first
                ) {
                    activeList = index
                    saveLists()
                    loadPlaylist(index)
                    showHome()
                },
                LinearLayout.LayoutParams(0, dp(60), 1f)
            )

            row.addView(
                button("✎") {
                    editList(index)
                }
            )

            row.addView(
                button("×") {
                    AlertDialog.Builder(this)
                        .setMessage(
                            tr(
                                "Удалить плейлист?",
                                "Playlist löschen?",
                                "Delete playlist?"
                            )
                        )
                        .setPositiveButton("OK") { _, _ ->
                            lists.removeAt(index)
                            activeList =
                                if (lists.isEmpty()) -1 else 0

                            saveLists()

                            if (activeList >= 0) {
                                loadPlaylist(activeList)
                            } else {
                                items.clear()
                            }

                            showPlaylists()
                        }
                        .setNegativeButton(
                            tr("Отмена", "Abbrechen", "Cancel"),
                            null
                        )
                        .show()
                }
            )

            content.addView(row)
        }
    }

    private fun editList(index: Int) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val name = EditText(this).apply {
            hint = tr("Название", "Name", "Name")
            setSingleLine(true)
            setText(lists.getOrNull(index)?.first ?: "")
        }

        val url = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setText(lists.getOrNull(index)?.second ?: "")
        }

        form.addView(name)
        form.addView(url)

        AlertDialog.Builder(this)
            .setTitle("M3U")
            .setView(form)
            .setPositiveButton(
                tr("Сохранить", "Speichern", "Save")
            ) { _, _ ->

                val value = url.text.toString().trim()

                if (!value.startsWith("https://") &&
                    !value.startsWith("http://")
                ) {
                    toast("HTTP(S) URL required")
                    return@setPositiveButton
                }

                val title = name.text.toString()
                    .trim()
                    .ifEmpty { "Playlist ${lists.size + 1}" }

                if (index >= 0) {
                    lists[index] = title to value
                } else {
                    lists.add(title to value)
                }

                activeList =
                    if (index >= 0) index else lists.lastIndex

                saveLists()
                loadPlaylist(activeList)
                showPlaylists()
            }
            .setNegativeButton(
                tr("Отмена", "Abbrechen", "Cancel"),
                null
            )
            .show()
    }

    private fun loadPlaylist(index: Int) {
        val url = lists.getOrNull(index)?.second ?: return

        thread {
            try {
                val connection =
                    URL(url).openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                val parsed = parseM3u(body)

                runOnUiThread {
                    if (index == activeList) {
                        items.clear()
                        items.addAll(parsed)
                        toast("${parsed.size} items")
                        showHome()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("M3U: ${e.message}")
                }
            }
        }
    }

    private fun parseM3u(raw: String): List<Entry> {
        val result = ArrayList<Entry>()

        var title = ""
        var group = ""
        var logo = ""

        val attributes = Regex(
            "([\\w-]+)=\"([^\"]*)\""
        )

        for (line in raw.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXTINF", true)) {
                val attrs = attributes.findAll(line)
                    .associate {
                        it.groupValues[1] to it.groupValues[2]
                    }

                title = line.substringAfterLast(
                    ',',
                    attrs["tvg-name"] ?: "Stream"
                ).trim()

                group = attrs["group-title"] ?: "Other"
                logo = attrs["tvg-logo"] ?: ""

            } else if (
                line.startsWith("http://") ||
                line.startsWith("https://")
            ) {
                val g = group.lowercase()

                val type = when {
                    listOf(
                        "series", "сериал", "serie"
                    ).any { g.contains(it) } -> "series"

                    listOf(
                        "movie", "film", "фильм",
                        "кино", "vod"
                    ).any { g.contains(it) } -> "movies"

                    else -> "tv"
                }

                result.add(
                    Entry(
                        title.ifBlank { "Stream" },
                        line,
                        group.ifBlank { "Other" },
                        logo,
                        type
                    )
                )

                title = ""
                group = ""
                logo = ""
            }
        }

        return result
    }

    private fun play(entry: Entry) {
        prefs.edit()
            .putString("recent", entry.url)
            .apply()

        scaffold("player")
        sectionTitle(entry.title)

        val view = PlayerView(this).apply {
            useController = true
            isFocusable = true
        }

        playerView = view
        view.player = player

        content.addView(
            view,
            LinearLayout.LayoutParams(
                -1,
                if (isPhone())
                    resources.displayMetrics.widthPixels * 9 / 16
                else
                    dp(460)
            )
        )

        try {
            player?.setMediaItem(
                MediaItem.fromUri(Uri.parse(entry.url))
            )
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            toast(e.message ?: "Playback error")
        }

        content.addView(
            button(
                tr("Назад", "Zurück", "Back")
            ) {
                player?.pause()
                showCatalog(entry.type)
            }
        )
    }

    private fun showSettings() {
        scaffold("settings")

        sectionTitle(
            tr(
                "Настройки",
                "Einstellungen",
                "Settings"
            )
        )

        content.addView(
            label(
                tr(
                    "Язык интерфейса",
                    "Sprache",
                    "Interface language"
                )
            )
        )

        val languages = listOf(
            "Русский" to "ru",
            "Deutsch" to "de",
            "English" to "en"
        )

        languages.forEach { (name, code) ->
            content.addView(
                button(
                    (if (lang == code) "✓ " else "") + name
                ) {
                    lang = code
                    prefs.edit()
                        .putString("language", code)
                        .apply()
                    showSettings()
                }
            )
        }

        content.addView(
            button(
                tr(
                    "Управление плейлистами",
                    "Playlisten verwalten",
                    "Manage playlists"
                )
            ) {
                showPlaylists()
            }
        )

        content.addView(
            button("QR / Phone") {
                showRemote()
            }
        )
    }

    private fun localIp(): String? {
        return try {
            val interfaces =
                NetworkInterface.getNetworkInterfaces()

            var ip: String? = null

            while (interfaces.hasMoreElements() && ip == null) {
                val network = interfaces.nextElement()

                if (!network.isLoopback && network.isUp) {
                    val addresses = network.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()

                        if (
                            address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress
                        ) {
                            ip = address.hostAddress
                            break
                        }
                    }
                }
            }

            ip
        } catch (_: Exception) {
            null
        }
    }

    private fun remoteUrl(): String? =
        localIp()?.let {
            "http://$it:8765/?key=$remoteToken"
        }

    private fun showRemote() {
        scaffold("remote")

        sectionTitle(
            tr(
                "Управление с телефона",
                "Steuerung mit Handy",
                "Phone control"
            )
        )

        val url = remoteUrl()

        if (url == null || !remoteRunning) {
            content.addView(
                label(
                    tr(
                        "Подключи телефон и ТВ к одному Wi-Fi",
                        "TV und Handy mit demselben WLAN verbinden",
                        "Connect TV and phone to the same Wi-Fi"
                    )
                )
            )
            return
        }

        content.addView(
            label(
                tr(
                    "Сканируй QR-код телефоном",
                    "QR-Code mit Handy scannen",
                    "Scan QR with phone"
                )
            )
        )

        try {
            val matrix = MultiFormatWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                420,
                420
            )

            val bitmap = Bitmap.createBitmap(
                420,
                420,
                Bitmap.Config.ARGB_8888
            )

            for (y in 0 until 420) {
                for (x in 0 until 420) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix.get(x, y))
                            Color.BLACK else Color.WHITE
                    )
                }
            }

            val image = ImageView(this)
            image.setImageBitmap(bitmap)

            content.addView(
                image,
                LinearLayout.LayoutParams(-1, dp(420))
            )

        } catch (e: Exception) {
            content.addView(
                label("QR error: ${e.message}")
            )
        }

        content.addView(label(url, 14f))
    }

    private fun startRemoteServer() {
        if (remoteRunning) return

        thread(name = "VladiMirRemote") {
            try {
                val server = ServerSocket(8765)
                remoteServer = server
                remoteRunning = true

                while (!server.isClosed) {
                    val client = server.accept()
                    thread {
                        handleRemote(client)
                    }
                }
            } catch (_: Exception) {
                remoteRunning = false
            }
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun handleRemote(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000

                val input = client.getInputStream()
                    .bufferedReader()

                val first = input.readLine() ?: return
                val parts = first.split(' ')

                if (parts.size < 2) return

                val uri = Uri.parse(parts[1])
                val path = uri.path ?: "/"
                val key = uri.getQueryParameter("key")

                var body = ""
                var status = "200 OK"

                if (key != remoteToken) {
                    status = "403 Forbidden"
                    body = "Forbidden"

                } else if (path == "/play") {
                    val index = uri.getQueryParameter("id")
                        ?.toIntOrNull()

                    if (index != null) {
                        runOnUiThread {
                            items.getOrNull(index)?.let {
                                play(it)
                            }
                        }
                    }

                    body = """
                        <p>Sent to TV</p>
                        <a href="/?key=$remoteToken">Back</a>
                    """.trimIndent()

                } else if (path == "/add") {
                    val name = uri.getQueryParameter("name")
                        ?.take(100)?.trim().orEmpty()

                    val url = uri.getQueryParameter("url")
                        ?.take(4096)?.trim().orEmpty()

                    if (!url.startsWith("https://") &&
                        !url.startsWith("http://")
                    ) {
                        status = "400 Bad Request"
                        body = "Invalid URL"
                    } else {
                        runOnUiThread {
                            lists.add(
                                name.ifBlank {
                                    "Playlist ${lists.size + 1}"
                                } to url
                            )

                            activeList = lists.lastIndex
                            saveLists()
                            loadPlaylist(activeList)
                        }

                        body = """
                            <meta charset="utf-8">
                            <p>Playlist added</p>
                            <a href="/?key=$remoteToken">Back</a>
                        """.trimIndent()
                    }

                } else if (path == "/" || path == "/search") {
                    val query =
                        uri.getQueryParameter("q") ?: ""

                    val matches = items.toList()
                        .withIndex()
                        .filter {
                            query.isBlank() ||
                            it.value.title.contains(query, true) ||
                            it.value.group.contains(query, true)
                        }
                        .take(80)

                    val links = matches.joinToString("<hr>") {
                        (index, entry) ->
                        """
                        <a href="/play?key=$remoteToken&id=$index">
                        ▶ ${escapeHtml(entry.title)}
                        </a>
                        <small>${escapeHtml(entry.group)}</small>
                        """.trimIndent()
                    }

                    body = """
                    <!doctype html>
                    <html lang="ru">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport"
                            content="width=device-width,initial-scale=1">
                      <title>VladiMir-TV Remote</title>
                      <style>
                        body {
                          background:#0c111d;
                          color:white;
                          font:18px sans-serif;
                          padding:18px;
                        }
                        input,button {
                          font:20px sans-serif;
                          padding:12px;
                          max-width:95%;
                        }
                        a {
                          color:#9ac6ff;
                          display:inline-block;
                          padding:8px;
                        }
                      </style>
                    </head>
                    <body>
                      <h2>VladiMir-TV</h2>
                      <p>Поиск / Suche / Search</p>
                      <form action="/search">
                        <input type="hidden"
                               name="key"
                               value="$remoteToken">
                        <input name="q"
                               value="${escapeHtml(query)}"
                               placeholder="Канал / Film / Channel">
                        <button>Поиск / Search</button>
                      </form>
                      <p>${matches.size} результатов</p>
                      $links
                      <hr>
                      <h3>Добавить M3U / Add playlist</h3>
                      <form action="/add">
                        <input type="hidden"
                               name="key"
                               value="$remoteToken">
                        <input name="name"
                               placeholder="Название / Name">
                        <input name="url"
                               type="url"
                               placeholder="https://..."
                               required>
                        <button>Добавить / Add</button>
                      </form>
                    </body>
                    </html>
                    """.trimIndent()

                } else {
                    status = "404 Not Found"
                    body = "Not found"
                }

                val bytes = body.toByteArray(Charsets.UTF_8)

                val headers = """
                    HTTP/1.1 $status
                    Content-Type: text/html; charset=utf-8
                    Content-Length: ${bytes.size}
                    Cache-Control: no-store
                    Connection: close

                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                client.getOutputStream().write(
                    headers.toByteArray(Charsets.UTF_8)
                )

                client.getOutputStream().write(bytes)
                client.getOutputStream().flush()

            } catch (_: Exception) {
            }
        }
    }

    private fun toast(value: String) {
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == "player") {
            player?.pause()
        }
        showHome()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        remoteServer?.close()
        remoteRunning = false
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
