package com.dreamstream.ultratv

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Standalone TV-friendly sports hub. RSS feeds must be licensed/allowed by their publishers. */
class SportsNewsActivity : Activity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("sports_news", MODE_PRIVATE) }
    private val bg = Color.rgb(9, 14, 30)
    private val foreground = Color.WHITE
    private val accent = Color.rgb(48, 207, 255)
    private var sport = "football"
    private var savedOnly = false
    private val sports = listOf("football", "cricket", "rugby", "tennis", "formula 1", "basketball", "golf", "boxing", "cycling", "other sports")
    // Set an approved RSS feed URL in Settings or via this activity's feed preference.
    // This deliberately does not scrape or redistribute publishers' full article bodies.
    private val feedUrl: String get() = prefs.getString("rss_feed", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHub()
    }

    private fun text(value: String, size: Float = 19f): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(foreground)
        setPadding(20, 14, 20, 14)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; isFocusable = true; setOnClickListener { action() }
        setOnFocusChangeListener { v, hasFocus -> v.setBackgroundColor(if (hasFocus) accent else Color.rgb(24, 36, 62)) }
        setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 36, 62))
    }
    private fun reset(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            setPadding(28, 18, 28, 18)
        }
        setContentView(root)
        root.addView(text(title, 30f))
    }
    private fun showHub() {
        reset("DREAMSTREAM  /  SPORTS NEWS")
        val nav = HorizontalScrollView(this)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sports.forEach { item -> tabs.addView(button(item.replaceFirstChar { it.uppercase() }) { sport = item; savedOnly = false; showHub() }) }
        tabs.addView(button("Saved articles") { savedOnly = true; showHub() })
        tabs.addView(button("Favourite teams") { manageTeams() })
        tabs.addView(button("RSS source") { configureFeed() })
        nav.addView(tabs); root.addView(nav)
        val scores = text("Scores: connect an authorised sports-data API for live results", 16f)
        scores.setTextColor(accent); root.addView(scores)
        val scroller = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(content); root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        if (savedOnly) {
            val items = savedArticles()
            if (items.isEmpty()) content.addView(text("No saved articles yet."))
            items.forEach { article -> addArticle(content, article) }
        } else if (feedUrl.isBlank()) {
            content.addView(text("Set a permitted RSS feed to load news. Football is the default category; other sports are selectable above."))
        } else {
            content.addView(text("Loading news…"))
            thread {
                val result = runCatching { fetchRss(feedUrl) }.getOrElse { emptyList() }
                runOnUiThread {
                    content.removeAllViews()
                    val matches = result.filter { a -> sport == "other sports" || sport == "football" || a.title.contains(sport, true) || a.description.contains(sport, true) }
                    if (matches.isEmpty()) content.addView(text("No stories found for $sport, or the feed is unavailable."))
                    matches.forEach { addArticle(content, it) }
                }
            }
        }
    }
    private fun addArticle(parent: LinearLayout, article: Article) {
        parent.addView(button(article.title + "\n" + article.description.take(160)) { showReader(article) })
    }
    private fun showReader(article: Article) {
        reset("SPORTS NEWS  /  ARTICLE")
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Back to news") { showHub() })
        controls.addView(button(if (isSaved(article.url)) "Unsave" else "Save article") { toggleSaved(article); showReader(article) })
        root.addView(controls)
        root.addView(text(article.title, 23f))
        root.addView(text("Publisher's website • use remote Up/Down to scroll", 14f))
        val browser = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            webViewClient = WebViewClient()
            isFocusable = true
            setBackgroundColor(Color.WHITE)
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { scrollBy(0, 260); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { scrollBy(0, -260); true }
                    else -> false
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        if (article.url.startsWith("https://")) browser.loadUrl(article.url)
        else root.addView(text("Only HTTPS article links are supported."))
    }
    private fun manageTeams() {
        reset("FAVOURITE TEAMS")
        val input = EditText(this).apply { hint = "Team name (e.g. Plymouth Argyle)"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true }
        root.addView(input)
        root.addView(button("Add team") {
            val team = input.text.toString().trim()
            if (team.isNotBlank()) prefs.edit().putStringSet("teams", (prefs.getStringSet("teams", emptySet()) ?: emptySet()) + team).apply()
            manageTeams()
        })
        (prefs.getStringSet("teams", emptySet()) ?: emptySet()).sorted().forEach { team ->
            root.addView(button("$team   •   Remove") {
                prefs.edit().putStringSet("teams", (prefs.getStringSet("teams", emptySet()) ?: emptySet()) - team).apply()
                manageTeams()
            })
        }
        root.addView(button("Back") { showHub() })
    }
    private fun configureFeed() {
        reset("NEWS SOURCE")
        root.addView(text("Enter an HTTPS RSS URL from a publisher that permits this use. Full articles open at the publisher's website."))
        val input = EditText(this).apply { setText(feedUrl); hint = "https://publisher.example/sports/rss"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine = true }
        root.addView(input)
        root.addView(button("Save source") {
            val value = input.text.toString().trim()
            if (value.isEmpty() || value.startsWith("https://")) prefs.edit().putString("rss_feed", value).apply()
            showHub()
        })
    }
    private data class Article(val title: String, val url: String, val description: String)
    private fun fetchRss(url: String): List<Article> {
        require(url.startsWith("https://"))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 9000; connection.readTimeout = 9000
        connection.setRequestProperty("User-Agent", "DreamStreamUltraTV/1.0")
        try {
            val parser = Xml.newPullParser()
            parser.setInput(connection.inputStream, "UTF-8")
            val result = mutableListOf<Article>()
            var inItem = false; var title = ""; var link = ""; var description = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && result.size < 80) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "item", "entry" -> { inItem = true; title = ""; link = ""; description = "" }
                        "title" -> if (inItem) title = parser.nextText()
                        "description", "summary" -> if (inItem) description = parser.nextText().replace(Regex("<[^>]*>"), " ")
                        "link" -> if (inItem) {
                            val href = parser.getAttributeValue(null, "href")
                            link = href ?: runCatching { parser.nextText() }.getOrDefault("")
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name.lowercase() in listOf("item", "entry")) {
                    if (title.isNotBlank() && link.startsWith("https://")) result.add(Article(title, link, description))
                    inItem = false
                }
                event = parser.next()
            }
            return result
        } finally { connection.disconnect() }
    }
    private fun savedArticles(): List<Article> {
        val json = prefs.getString("saved_articles", "[]") ?: "[]"
        val array = org.json.JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            runCatching { array.getJSONObject(i).let { Article(it.optString("title"), it.optString("url"), it.optString("description")) } }.getOrNull()
        }
    }
    private fun isSaved(url: String) = savedArticles().any { it.url == url }
    private fun toggleSaved(article: Article) {
        val old = savedArticles()
        val next = if (old.any { it.url == article.url }) old.filterNot { it.url == article.url } else old + article
        val array = org.json.JSONArray()
        next.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url).put("description", it.description)) }
        prefs.edit().putString("saved_articles", array.toString()).apply()
    }
    override fun onBackPressed() { showHub() }
}
