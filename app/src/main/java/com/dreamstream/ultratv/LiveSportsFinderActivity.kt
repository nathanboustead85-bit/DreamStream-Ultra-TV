package com.dreamstream.ultratv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/** TV-friendly live sports discovery using user-supplied M3U and XMLTV sources.
 * Does not scrape streams, invent broadcast rights, or claim exhaustive coverage.
 */
class LiveSportsFinderActivity : Activity() {
    private data class Channel(val id: String, val name: String, val url: String)
    private data class Programme(val channelId: String, val title: String, val description: String,
                                 val start: Long, val stop: Long)
    private data class Match(val channel: Channel, val programme: Programme?, val confirmed: Boolean)
    private val prefs by lazy { getSharedPreferences("live_sports_finder", MODE_PRIVATE) }
    private val channels = mutableListOf<Channel>()
    private val programmes = mutableListOf<Programme>()
    private val teams = mutableListOf<String>()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private val background = Color.rgb(9, 14, 30)
    private val panel = Color.rgb(23, 35, 62)
    private val accent = Color.rgb(48, 207, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(background)
        restoreTeams()
        render()
        // Sources are fetched only on explicit refresh; no unapproved default feed is assumed.
    }

    private fun label(s: String, size: Float = 19f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.WHITE)
        setPadding(16, 12, 16, 12)
    }
    private fun action(s: String, block: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; isFocusable = true
        setOnClickListener { block() }
        setOnFocusChangeListener { v, focused ->
            v.setBackgroundColor(if (focused) accent else panel)
            (v as Button).setTextColor(if (focused) Color.BLACK else Color.WHITE)
        }
        setBackgroundColor(panel); setTextColor(Color.WHITE)
    }
    private fun section(s: String) { root.addView(label(s, 24f)) }
    private fun render() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 28)
        }
        scroll.addView(root)
        setContentView(scroll)
        section("LIVE SPORTS FINDER")
        root.addView(label("Favourite teams • Live channels • All sports", 16f))
        root.addView(action("Add favourite team / competitor") { prompt("Team or competitor name", "") { value ->
            if (value.isNotBlank() && teams.none { it.equals(value, true) }) {
                teams.add(value.trim()); saveTeams(); render()
            }
        } })
        root.addView(action("Set M3U playlist URL") { prompt("M3U playlist URL", prefs.getString("m3u", "") ?: "") {
            prefs.edit().putString("m3u", it.trim()).apply(); render()
        } })
        root.addView(action("Set XMLTV EPG URL (.xml or .xml.gz)") { prompt("XMLTV EPG URL", prefs.getString("epg", "") ?: "") {
            prefs.edit().putString("epg", it.trim()).apply(); render()
        } })
        root.addView(action("Refresh channels and programme guide") { refresh() })
        status = label("Channels: ${channels.size} • Guide programmes: ${programmes.size}", 15f)
        root.addView(status)
        section("MY FAVOURITES")
        if (teams.isEmpty()) root.addView(label("Add a team to search for its live broadcasts.", 16f))
        teams.forEach { team ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(action("★ $team — Find live channels") { showResults(team) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(action("Remove") { teams.remove(team); saveTeams(); render() })
            root.addView(line)
        }
        root.addView(label("Only channels found in the imported playlist can be opened. EPG matches are based on programme titles and descriptions; channel-name matches are unconfirmed.", 14f))
    }
    private fun prompt(title: String, initial: String, onSave: (String) -> Unit) {
        val field = EditText(this).apply { setSingleLine(true); setText(initial); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton("Save") { _, _ -> onSave(field.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun restoreTeams() {
        val arr = runCatching { JSONArray(prefs.getString("teams", "[]")) }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) teams.add(arr.optString(i))
    }
    private fun saveTeams() {
        val arr = JSONArray(); teams.forEach { arr.put(it) }
        prefs.edit().putString("teams", arr.toString()).apply()
    }
    private fun refresh() {
        val m3u = prefs.getString("m3u", "")?.trim().orEmpty()
        val epg = prefs.getString("epg", "")?.trim().orEmpty()
        if (m3u.isBlank()) { status.text = "Set an M3U playlist URL first."; return }
        status.text = "Loading playlist and guide…"
        thread {
            try {
                val loadedChannels = fetch(m3u).bufferedReader().use { parseM3u(it.readText()) }
                val loadedGuide = if (epg.isNotBlank()) fetch(epg).use { parseXmltv(it) } else emptyList()
                runOnUiThread {
                    channels.clear(); channels.addAll(loadedChannels)
                    programmes.clear(); programmes.addAll(loadedGuide)
                    render()
                    status.text = "Loaded ${channels.size} channels and ${programmes.size} programmes."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Import failed: ${e.message ?: "unknown error"}" }
            }
        }
    }
    private fun fetch(address: String): InputStream {
        val url = URL(address)
        require(url.protocol == "https" || url.protocol == "http") { "Only HTTP(S) sources are supported" }
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "DreamStreamUltraTV/1.0")
        conn.connect()
        require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
        val raw = BufferedInputStream(conn.inputStream)
        raw.mark(4)
        val a = raw.read(); val b = raw.read(); raw.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(raw) else raw
    }
    private fun parseM3u(content: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var name = ""; var id = ""
        val attr = Regex("tvg-id=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        content.lineSequence().forEach { lineRaw ->
            val line = lineRaw.trim()
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',', "").trim()
                id = attr.find(line)?.groupValues?.get(1).orEmpty()
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                if (name.isNotEmpty()) result.add(Channel(id, name, line))
                name = ""; id = ""
            }
        }
        return result
    }
    private fun parseXmltv(stream: InputStream): List<Programme> {
        val result = mutableListOf<Programme>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")
        var currentId = ""; var start = 0L; var stop = 0L
        var title = ""; var description = ""; var field: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        currentId = parser.getAttributeValue(null, "channel").orEmpty()
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = ""; description = ""
                    }
                    "title", "desc" -> field = parser.name
                }
                XmlPullParser.TEXT -> when (field) {
                    "title" -> title += parser.text
                    "desc" -> description += parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc" -> field = null
                    "programme" -> {
                        if (currentId.isNotBlank() && stop > start) {
                            result.add(Programme(currentId, title, description, start, stop))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return result
    }
    private fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        val compact = trimmed.take(14)
        return runCatching {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }
            val date = fmt.parse(compact) ?: return 0L
            val offset = Regex("([+-])(\\d{2})(\\d{2})$").find(trimmed)
            if (offset != null) {
                val minutes = offset.groupValues[2].toLong() * 60 + offset.groupValues[3].toLong()
                val sign = if (offset.groupValues[1] == "+") 1 else -1
                // SimpleDateFormat initially uses device timezone; reinterpret as UTC then subtract offset.
                val utc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
                }.parse(compact)!!.time
                utc - sign * minutes * 60_000L
            } else date.time
        }.getOrDefault(0L)
    }
    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun matches(text: String, team: String): Boolean {
        val needle = normalize(team)
        if (needle.length < 3) return false
        return " ${normalize(text)} ".contains(" $needle ")
    }
    private fun showResults(team: String) {
        val now = System.currentTimeMillis()
        val live = programmes.filter { it.start <= now && now < it.stop &&
            matches(it.title + " " + it.description, team) }
        val matches = mutableListOf<Match>()
        val used = mutableSetOf<String>()
        // Exact tvg-id preferred; fallback to normalised channel names only if ID is absent.
        for (programme in live) {
            val matchingChannels = channels.filter { channel ->
                if (channel.id.isNotBlank()) channel.id.equals(programme.channelId, true)
                else normalize(channel.name) == normalize(programme.channelId)
            }
            for (channel in matchingChannels) if (used.add(channel.url)) matches.add(Match(channel, programme, true))
        }
        for (channel in channels) {
            if (used.add(channel.url) && matches(channel.name, team)) matches.add(Match(channel, null, false))
        }
        val scroll = ScrollView(this)
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        scroll.addView(view); setContentView(scroll)
        view.addView(label("$team — LIVE CHANNELS", 25f))
        view.addView(action("← Back to favourites") { render() })
        view.addView(label("${matches.size} matching playlist channels • ${Date(now)}", 15f))
        if (matches.isEmpty()) view.addView(label("No live channel matches found. Check the EPG URL, channel mappings and playlist coverage.", 17f))
        matches.forEach { match ->
            val evidence = if (match.confirmed) "EPG-confirmed programme match" else "Possible match — channel name only"
            view.addView(action("▶ ${match.channel.name}\n$evidence${match.programme?.let { "\n${it.title}" } ?: ""}") {
                // Open via Android's installed stream handler. Replace with app's ExoPlayer screen
                // when integrating; never send provider credentials to a third-party app silently.
                AlertDialog.Builder(this).setTitle(match.channel.name)
                    .setMessage("Open this channel with an installed player? Stream URLs may contain credentials.")
                    .setPositiveButton("Open") { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(match.channel.url))) }
                            .onFailure { Toast.makeText(this, "No compatible player installed", Toast.LENGTH_LONG).show() }
                    }.setNegativeButton("Cancel", null).show()
            })
        }
    }
    override fun onBackPressed() { render() }
                         }
