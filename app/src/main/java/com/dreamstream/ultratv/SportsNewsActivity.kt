package com.dreamstream.ultratv

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import android.util.Xml
import org.json.JSONObject
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * DreamStream Ultra TV - Sports News Hub
 *
 * Features:
 * - Sports categories
 * - HTTPS RSS and Atom feeds
 * - Saved articles
 * - Favourite teams
 * - TV remote navigation
 * - Full-screen article reader
 */
class SportsNewsActivity : Activity() {

    private lateinit var root: LinearLayout

    private val prefs by lazy {
        getSharedPreferences("sports_news", MODE_PRIVATE)
    }

    private val bg = Color.rgb(9, 14, 30)
    private val textColour = Color.WHITE
    private val accent = Color.rgb(48, 207, 255)
    private val panel = Color.rgb(24, 36, 62)

    private var sport = "football"
    private var savedOnly = false

    private val sports = listOf(
        "football",
        "cricket",
        "rugby",
        "tennis",
        "formula 1",
        "basketball",
        "golf",
        "boxing",
        "cycling",
        "other sports"
    )

    private val feedUrl: String
        get() = prefs.getString("rss_feed", "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHub()
    }

    private fun makeText(
        value: String,
        size: Float = 19f
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(textColour)
            setPadding(20, 14, 20, 14)
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            isFocusable = true

            setTextColor(Color.WHITE)
            setBackgroundColor(panel)

            setOnClickListener {
                action()
            }

            setOnFocusChangeListener { view, focused ->
                view.setBackgroundColor(
                    if (focused) accent else panel
                )

                (view as Button).setTextColor(
                    if (focused) Color.BLACK else Color.WHITE
                )
            }
        }
    }

    private fun reset(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(28, 18, 28, 18)
        }

        setContentView(root)
        root.addView(makeText(title, 30f))
    }

    private fun showHub() {
        reset("DREAMSTREAM / SPORTS NEWS")

        val navigation = HorizontalScrollView(this)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        sports.forEach { category ->
            tabs.addView(
                makeButton(
                    category.replaceFirstChar {
                        it.uppercase()
                    }
                ) {
                    sport = category
                    savedOnly = false
                    showHub()
                }
            )
        }

        tabs.addView(
            makeButton("Saved articles") {
                savedOnly = true
                showHub()
            }
        )

        tabs.addView(
            makeButton("Favourite teams") {
                manageTeams()
            }
        )

        tabs.addView(
            makeButton("RSS source") {
                configureFeed()
            }
        )

        navigation.addView(tabs)
        root.addView(navigation)

        val scores = makeText(
            "Live scores require an authorised sports-data API.",
            16f
        )

        scores.setTextColor(accent)
        root.addView(scores)

        val scroller = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroller.addView(content)

        root.addView(
            scroller,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        if (savedOnly) {
            val items = savedArticles()

            if (items.isEmpty()) {
                content.addView(
                    makeText("No saved articles yet.")
                )
            }

            items.forEach { article ->
                addArticle(content, article)
            }

        } else if (feedUrl.isBlank()) {

            content.addView(
                makeText(
                    "Set a permitted RSS feed to load sports news."
                )
            )

        } else {

            content.addView(
                makeText("Loading sports news...")
            )

            val selectedSport = sport

            thread {
                val result = runCatching {
                    fetchRss(feedUrl)
                }.getOrElse {
                    emptyList()
                }

                runOnUiThread {
                    content.removeAllViews()

                    val matches = result.filter { article ->
                        selectedSport == "other sports" ||
                        selectedSport == "football" ||
                        article.title.contains(
                            selectedSport,
                            ignoreCase = true
                        ) ||
                        article.description.contains(
                            selectedSport,
                            ignoreCase = true
                        )
                    }

                    if (matches.isEmpty()) {
                        content.addView(
                            makeText(
                                "No stories found for $selectedSport, or the feed is unavailable."
                            )
                        )
                    }

                    matches.forEach { article ->
                        addArticle(content, article)
                    }
                }
            }
        }
    }

    private fun addArticle(
        parent: LinearLayout,
        article: Article
    ) {
        parent.addView(
            makeButton(
                article.title + "\n" +
                article.description.take(160)
            ) {
                showReader(article)
            }
        )
    }

    private fun showReader(article: Article) {
        reset("SPORTS NEWS / ARTICLE")

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        controls.addView(
            makeButton("Back to news") {
                showHub()
            }
        )

        controls.addView(
            makeButton(
                if (isSaved(article.url)) {
                    "Unsave"
                } else {
                    "Save article"
                }
            ) {
                toggleSaved(article)
                showReader(article)
            }
        )

        root.addView(controls)

        root.addView(
            makeText(article.title, 23f)
        )

        root.addView(
            makeText(
                "Publisher website - use remote Up/Down to scroll.",
                14f
            )
        )

        val browser = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false

            webViewClient = WebViewClient()
            isFocusable = true
            setBackgroundColor(Color.WHITE)

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    false
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            scrollBy(0, 260)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_UP -> {
                            scrollBy(0, -260)
                            true
                        }

                        else -> false
                    }
                }
            }
        }

        root.addView(
            browser,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        if (article.url.startsWith("https://")) {
            browser.loadUrl(article.url)
        } else {
            root.addView(
                makeText(
                    "Only HTTPS article links are supported."
                )
            )
        }
    }

    private fun manageTeams() {
        reset("FAVOURITE TEAMS")

        val input = EditText(this).apply {
            hint = "Team name (e.g. Plymouth Argyle)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            isSingleLine = true
        }

        root.addView(input)

        root.addView(
            makeButton("Add team") {
                val team = input.text.toString().trim()

                if (team.isNotBlank()) {
                    val current = prefs.getStringSet(
                        "teams",
                        emptySet()
                    ) ?: emptySet()

                    prefs.edit()
                        .putStringSet(
                            "teams",
                            current + team
                        )
                        .apply()
                }

                manageTeams()
            }
        )

        val teams = prefs.getStringSet(
            "teams",
            emptySet()
        ) ?: emptySet()

        teams.sorted().forEach { team ->
            root.addView(
                makeButton("$team - Remove") {
                    val current = prefs.getStringSet(
                        "teams",
                        emptySet()
                    ) ?: emptySet()

                    prefs.edit()
                        .putStringSet(
                            "teams",
                            current - team
                        )
                        .apply()

                    manageTeams()
                }
            )
        }

        root.addView(
            makeButton("Back") {
                showHub()
            }
        )
    }

    private fun configureFeed() {
        reset("NEWS SOURCE")

        root.addView(
            makeText(
                "Enter an HTTPS RSS URL from a publisher that permits this use."
            )
        )

        val input = EditText(this).apply {
            setText(feedUrl)
            hint = "https://publisher.example/sports/rss"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            isSingleLine = true
        }

        root.addView(input)

        root.addView(
            makeButton("Save source") {
                val value = input.text.toString().trim()

                if (
                    value.isEmpty() ||
                    value.startsWith("https://")
                ) {
                    prefs.edit()
                        .putString("rss_feed", value)
                        .apply()
                } else {
                    Toast.makeText(
                        this,
                        "Please enter an HTTPS RSS URL.",
                        Toast.LENGTH_LONG
                    ).show()
                }

                showHub()
            }
        )
    }

    private data class Article(
        val title: String,
        val url: String,
        val description: String
    )

    private fun fetchRss(
        url: String
    ): List<Article> {

        require(url.startsWith("https://"))

        val connection = URL(url)
            .openConnection() as HttpURLConnection

        connection.connectTimeout = 9000
        connection.readTimeout = 9000

        connection.setRequestProperty(
            "User-Agent",
            "DreamStreamUltraTV/1.0"
        )

        try {
            val parser = Xml.newPullParser()

            parser.setInput(
                connection.inputStream,
                "UTF-8"
            )

            val result = mutableListOf<Article>()

            var inItem = false
            var title = ""
            var link = ""
            var description = ""

            var event = parser.eventType

            while (
                event != XmlPullParser.END_DOCUMENT &&
                result.size < 80
            ) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {

                        "item", "entry" -> {
                            inItem = true
                            title = ""
                            link = ""
                            description = ""
                        }

                        "title" -> {
                            if (inItem) {
                                title = parser.nextText()
                            }
                        }

                        "description", "summary" -> {
                            if (inItem) {
                                description = parser.nextText()
                                    .replace(
                                        Regex("<[^>]*>"),
                                        " "
                                    )
                            }
                        }

                        "link" -> {
                            if (inItem) {
                                val href = parser.getAttributeValue(
                                    null,
                                    "href"
                                )

                                link = href ?: runCatching {
                                    parser.nextText()
                                }.getOrDefault("")
                            }
                        }
                    }
                } else if (
                    event == XmlPullParser.END_TAG &&
                    parser.name.lowercase() in
                    listOf("item", "entry")
                ) {
                    if (
                        title.isNotBlank() &&
                        link.startsWith("https://")
                    ) {
                        result.add(
                            Article(
                                title,
                                link,
                                description
                            )
                        )
                    }

                    inItem = false
                }

                event = parser.next()
            }

            return result

        } finally {
            connection.disconnect()
        }
    }

    private fun savedArticles(): List<Article> {
        val json = prefs.getString(
            "saved_articles",
            "[]"
        ) ?: "[]"

        val array = runCatching {
            JSONArray(json)
        }.getOrElse {
            JSONArray()
        }

        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                array.getJSONObject(index).let {
                    Article(
                        it.optString("title"),
                        it.optString("url"),
                        it.optString("description")
                    )
                }
            }.getOrNull()
        }
    }

    private fun isSaved(url: String): Boolean {
        return savedArticles().any {
            it.url == url
        }
    }

    private fun toggleSaved(article: Article) {
        val old = savedArticles()

        val next = if (
            old.any { it.url == article.url }
        ) {
            old.filterNot {
                it.url == article.url
            }
        } else {
            old + article
        }

        val array = JSONArray()

        next.forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("description", item.description)
            )
        }

        prefs.edit()
            .putString(
                "saved_articles",
                array.toString()
            )
            .apply()
    }

    @Deprecated("Use OnBackPressedDispatcher where available")
    override fun onBackPressed() {
        showHub()
    }
}
