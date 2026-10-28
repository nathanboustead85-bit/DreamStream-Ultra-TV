package com.dreamstream.ultratv

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class LiveTvActivity : Activity() {

    private val navy = Color.rgb(9, 14, 30)
    private val cyan = Color.rgb(48, 207, 255)
    private val panel = Color.rgb(23, 34, 57)

    private val executor =
        Executors.newFixedThreadPool(3)

    private lateinit var root: LinearLayout
    private lateinit var account: XtreamApi.Account

    private var channels =
        emptyList<XtreamApi.Stream>()

    private var categories =
        emptyList<Pair<String, String>>()

    private var logos =
        emptyMap<String, String>()

    private var selectedCategory = ""
    private var searchQuery = ""
    private var mode = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serverIndex = intent.getIntExtra(
            "server_index", 0
        ).coerceIn(0, XtreamApi.servers.lastIndex)

        val username =
            intent.getStringExtra("username").orEmpty()

        val password =
            intent.getStringExtra("password").orEmpty()

        if (username.isBlank() || password.isBlank()) {
            finish()
            return
        }

        account = XtreamApi.Account(
            XtreamApi.servers[serverIndex],
            username,
            password
        )

        showLoading()
        loadChannels()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f)
            .toInt()

    private fun label(
        value: String,
        size: Float = 16f,
        colour: Int = Color.WHITE
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(colour)
        setPadding(
            dp(10), dp(10),
            dp(10), dp(10)
        )
    }

    private fun button(
        value: String,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = value
        isAllCaps = false
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun screen(title: String) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(navy)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(14), dp(12),
                dp(14), dp(18)
            )
        }

        scroll.addView(root)
        setContentView(scroll)

        root.addView(
            label(title, 25f, cyan).apply {
                setTypeface(null, Typeface.BOLD)
            }
        )
    }

    private fun showLoading() {
        screen("DREAMSTREAM • LIVE TV")
        root.addView(
            label("Loading channels and categories...")
        )
    }

    private fun loadChannels() {
        executor.execute {
            try {
                val foundCategories =
                    XtreamApi.categories(account, "live")

                val foundChannels =
                    XtreamApi.streams(account, "live")

                val foundLogos = loadLogos()

                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    categories = foundCategories
                    channels = foundChannels
                    logos = foundLogos

                    showLibrary()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }

                    screen("LIVE TV")

                    root.addView(
                        label(
                            error.message
                                ?: "Unable to load channels"
                        )
                    )

                    root.addView(
                        button("RETRY") {
                            showLoading()
                            loadChannels()
                        }
                    )
                }
            }
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun loadLogos(): Map<String, String> {
        val base = account.server.url.trimEnd('/')

        val url = "$base/player_api.php" +
            "?username=${encode(account.username)}" +
            "&password=${encode(account.password)}" +
            "&action=get_live_streams"

        val connection =
            URL(url).openConnection() as HttpURLConnection

        connection.connectTimeout = 12000
        connection.readTimeout = 20000

        try {
            if (connection.responseCode !in 200..299) {
                return emptyMap()
            }

            val raw = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val json = JSONArray(raw)
            val result = mutableMapOf<String, String>()

            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index)
                    ?: continue

                val id = item.optString("stream_id")
                val icon = item.optString("stream_icon")

                if (
                    id.isNotBlank() &&
                    icon.startsWith("https://", true)
                ) {
                    result[id] = icon
                }
            }

            return result
        } catch (_: Exception) {
            return emptyMap()
        } finally {
            connection.disconnect()
        }
    }

    private fun channelLogo(
        stream: XtreamApi.Stream
    ): ImageView {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(
                android.R.drawable.ic_menu_gallery
            )
        }

        val url = logos[stream.id]

        if (url != null) {
            executor.execute {
                try {
                    val connection =
                        URL(url).openConnection()
                            as HttpURLConnection

                    connection.connectTimeout = 6000
                    connection.readTimeout = 6000

                    try {
                        if (
                            connection.responseCode
                            !in 200..299
                        ) {
                            return@execute
                        }

                        val bitmap =
                            connection.inputStream.use {
                                BitmapFactory.decodeStream(it)
                            }

                        if (bitmap != null) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    image.setImageBitmap(bitmap)
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (_: Exception) {
                }
            }
        }

        return image
    }

    private fun play(
        stream: XtreamApi.Stream
    ) {
        RecentChannelsStore.add(
            this, account, stream
        )

        startActivity(
            Intent(
                this,
                PlayerActivity::class.java
            ).putExtra(
                "url",
                XtreamApi.playbackUrl(account, stream)
            )
        )
    }

    private fun showLibrary() {
        screen("DREAMSTREAM • LIVE TV")

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        root.addView(tabs)

        listOf(
            "ALL" to "all",
            "RECENT" to "recent",
            "FAVOURITES" to "favourites"
        ).forEach { (title, value) ->
            tabs.addView(
                button(title) {
                    mode = value
                    showLibrary()
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        root.addView(
            label(
                when (mode) {
                    "recent" -> "RECENTLY WATCHED"
                    "favourites" -> "FAVOURITE CHANNELS"
                    else -> "ALL LIVE CHANNELS"
                },
                17f,
                cyan
            )
        )

        val search = EditText(this).apply {
            hint = "Search channels"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(searchQuery)
        }

        root.addView(search)

        val options = listOf(
            "" to "All categories"
        ) + categories

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LiveTvActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.second }
            )

            val index = options.indexOfFirst {
                it.first == selectedCategory
            }

            setSelection(index.coerceAtLeast(0))
        }

        root.addView(spinner)

        val count = label("", 14f)
        root.addView(count)

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(results)

        fun render() {
            results.removeAllViews()

            val source = when (mode) {
                "recent" ->
                    RecentChannelsStore.list(
                        this, account
                    )

                "favourites" ->
                    FavouritesStore.list(
                        this, account
                    ).filter {
                        it.type == "live"
                    }

                else -> channels
            }

            val filtered = source.filter {
                (selectedCategory.isBlank() ||
                    it.categoryId == selectedCategory) &&
                    (searchQuery.isBlank() ||
                        it.title.contains(
                            searchQuery,
                            ignoreCase = true
                        ))
            }

            count.text =
                "${filtered.size} channels • ${account.server.name}"

            if (filtered.isEmpty()) {
                results.addView(
                    label(
                        "No channels found in this section."
                    )
                )
            }

            filtered.take(100).forEach { stream ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(5), dp(5),
                        dp(5), dp(5)
                    )
                }

                val logo = channelLogo(stream)

                row.addView(
                    logo,
                    LinearLayout.LayoutParams(
                        dp(55),
                        dp(55)
                    )
                )

                val open = button(stream.title) {
                    play(stream)
                }.apply {
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(Color.WHITE)

                    background =
                        GradientDrawable().apply {
                            setColor(panel)
                            cornerRadius = dp(8).toFloat()
                            setStroke(dp(1), cyan)
                        }
                }

                row.addView(
                    open,
                    LinearLayout.LayoutParams(
                        0,
                        dp(65),
                        1f
                    )
                )

                val saved = FavouritesStore.contains(
                    this, account, stream
                )

                row.addView(
                    button(if (saved) "★" else "☆") {
                        FavouritesStore.toggle(
                            this, account, stream
                        )
                        render()
                    }
                )

                results.addView(row)
            }

            if (filtered.size > 100) {
                results.addView(
                    label(
                        "Showing 100 channels. " +
                        "Search or select a category to narrow results."
                    )
                )
            }
        }

        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) = Unit

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedCategory =
                        options[position].first
                    render()
                }
            }

        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    searchQuery = s.toString()
                    render()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        render()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
