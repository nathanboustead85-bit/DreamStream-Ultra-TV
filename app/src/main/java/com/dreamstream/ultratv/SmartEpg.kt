package com.dreamstream.ultratv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

object SmartEpg {
    private const val BASE = "https://epgshare01.online/epgshare01/"
    private const val CACHE_MS = 12L * 60 * 60 * 1000
    private const val MAX_BYTES = 20L * 1024 * 1024

    private val countries = listOf(
        "United Kingdom" to "UK1",
        "United States" to "US2",
        "Canada" to "CA2",
        "New Zealand" to "NZ1"
    )

    private val navy = Color.rgb(9, 14, 30)
    private val cyan = Color.rgb(48, 207, 255)
    private val muted = Color.rgb(180, 196, 219)
    private val panel = Color.rgb(23, 34, 57)

    private data class Programme(
        val start: Long,
        val end: Long,
        val title: String
    )

    private data class Channel(
        val code: String,
        val country: String,
        val id: String,
        val name: String
    ) {
        val key: String get() = "$code|$id"
    }

    private data class Listing(
        val channel: Channel,
        val programmes: List<Programme>,
        val manual: Boolean
    )

    private data class Feed(
        val channels: List<Channel>,
        val listings: Map<String, Listing>
    )

    private data class Result(
        val channels: List<Channel>,
        val listings: Map<String, Listing>,
        val messages: List<String>
    )

    private fun dp(activity: Activity, n: Int) =
        (n * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun normalise(value: String) =
        value.lowercase(Locale.ROOT)
            .replace(
                Regex("\\b(uk|us|usa|ca|canada|nz|hd|fhd|uhd|4k|sd|hevc|h265)\\b"),
                " "
            )
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun digest(value: String) =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun prefs(activity: Activity) =
        activity.getSharedPreferences(
            "dreamstream_manual_epg",
            Activity.MODE_PRIVATE
        )

    private fun key(
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ) = digest(
        "${account.server.url}|${account.username}|${stream.id}"
    )

    private fun saved(
        activity: Activity,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ) = prefs(activity).getString(
        key(account, stream),
        null
    )

    private fun save(
        activity: Activity,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream,
        channel: Channel?
    ) {
        val editor = prefs(activity).edit()

        if (channel == null) {
            editor.remove(key(account, stream))
        } else {
            editor.putString(
                key(account, stream),
                channel.key
            )
        }

        editor.apply()
    }

    private fun parseDate(value: String): Long {
        for (
            pattern in listOf(
                "yyyyMMddHHmmss Z",
                "yyyyMMddHHmm Z",
                "yyyyMMddHHmmss",
                "yyyyMMddHHmm"
            )
        ) {
            try {
                val formatter = SimpleDateFormat(
                    pattern,
                    Locale.US
                ).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val result = formatter.parse(value.trim())

                if (result != null) {
                    return result.time
                }
            } catch (_: Exception) {
            }
        }

        return 0L
    }

    private fun format(
        ms: Long,
        pattern: String
    ) = SimpleDateFormat(
        pattern,
        Locale.UK
    ).format(Date(ms))

    private fun dayStart(offset: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offset)
        }.timeInMillis

    private fun cache(
        activity: Activity,
        code: String
    ) = File(
        activity.cacheDir,
        "dreamstream_epg_$code.xml.gz"
    )

    private fun obtain(
        activity: Activity,
        code: String,
        force: Boolean
    ): File {
        val target = cache(activity, code)

        val timestamp = activity.getSharedPreferences(
            "dreamstream_epg_cache",
            Activity.MODE_PRIVATE
        ).getLong(code, 0)

        if (
            !force &&
            target.exists() &&
            System.currentTimeMillis() - timestamp in 0 until CACHE_MS
        ) {
            return target
        }

        val temp = File(
            activity.cacheDir,
            "dreamstream_epg_$code.tmp"
        )

        val connection = URL(
            "${BASE}epg_ripper_${code}.xml.gz"
        ).openConnection() as HttpURLConnection

        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true

        try {
            check(
                connection.responseCode in 200..299
            ) {
                "EPG HTTP ${connection.responseCode}"
            }

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val bytes = ByteArray(8192)
                    var total = 0L

                    while (true) {
                        val n = input.read(bytes)

                        if (n < 0) break

                        total += n

                        check(total <= MAX_BYTES) {
                            "EPG feed exceeds 20 MB"
                        }

                        output.write(bytes, 0, n)
                    }
                }
            }

            GZIPInputStream(
                temp.inputStream()
            ).use {
                check(it.read() >= 0) {
                    "Empty EPG gzip"
                }
            }

            temp.copyTo(
                target,
                overwrite = true
            )

            activity.getSharedPreferences(
                "dreamstream_epg_cache",
                Activity.MODE_PRIVATE
            ).edit()
                .putLong(
                    code,
                    System.currentTimeMillis()
                )
                .apply()

            return target

        } catch (error: Exception) {
            if (target.exists()) {
                return target
            }

            throw error

        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun parseFeed(
        file: File,
        code: String,
        country: String,
        streams: List<XtreamApi.Stream>,
        manual: Map<String, String>
    ): Feed {

        val wanted = streams.map {
            normalise(it.title)
        }.filter {
            it.isNotEmpty()
        }.toSet()

        val manuallyWanted = manual.values.toSet()

        val channels = mutableListOf<Channel>()

        val aliases =
            mutableMapOf<String, MutableSet<String>>()

        val selected =
            mutableMapOf<String, Channel>()

        val programmes =
            mutableMapOf<String, MutableList<Programme>>()

        val now = System.currentTimeMillis()
        val limit = dayStart(7)

        GZIPInputStream(
            file.inputStream().buffered()
        ).buffered().use { input ->

            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")

            var event = parser.eventType
            var inChannel = false
            var inProgramme = false

            var channelId = ""
            var channelName = ""
            var programmeChannel = ""

            var start = 0L
            var end = 0L
            var title = ""

            while (
                event != XmlPullParser.END_DOCUMENT
            ) {
                if (
                    event == XmlPullParser.START_TAG
                ) {
                    when (parser.name) {

                        "channel" -> {
                            inChannel = true

                            channelId =
                                parser.getAttributeValue(
                                    null,
                                    "id"
                                ).orEmpty()

                            channelName = ""

                            aliases.getOrPut(
                                channelId
                            ) {
                                mutableSetOf()
                            }.add(normalise(channelId))
                        }

                        "display-name" -> {
                            if (inChannel) {
                                val name = parser.nextText()

                                if (channelName.isBlank()) {
                                    channelName = name
                                }

                                aliases.getOrPut(
                                    channelId
                                ) {
                                    mutableSetOf()
                                }.add(normalise(name))
                            }
                        }

                        "programme" -> {
                            inProgramme = true

                            programmeChannel =
                                parser.getAttributeValue(
                                    null,
                                    "channel"
                                ).orEmpty()

                            start = parseDate(
                                parser.getAttributeValue(
                                    null,
                                    "start"
                                ).orEmpty()
                            )

                            end = parseDate(
                                parser.getAttributeValue(
                                    null,
                                    "stop"
                                ).orEmpty()
                            )

                            title = ""
                        }

                        "title" -> {
                            if (inProgramme) {
                                title = parser.nextText()
                            }
                        }
                    }
                }

                if (
                    event == XmlPullParser.END_TAG
                ) {
                    when (parser.name) {

                        "channel" -> {
                            if (channelId.isNotBlank()) {
                                val channel = Channel(
                                    code,
                                    country,
                                    channelId,
                                    channelName.ifBlank {
                                        channelId
                                    }
                                )

                                channels.add(channel)

                                val names =
                                    aliases.getOrPut(
                                        channelId
                                    ) {
                                        mutableSetOf()
                                    }

                                names.add(
                                    normalise(channel.name)
                                )

                                if (
                                    names.any {
                                        it in wanted
                                    } ||
                                    channel.key in manuallyWanted
                                ) {
                                    selected[channelId] =
                                        channel
                                }
                            }

                            inChannel = false
                        }

                        "programme" -> {
                            if (
                                inProgramme &&
                                programmeChannel in selected &&
                                end > now &&
                                start < limit &&
                                end > start
                            ) {
                                programmes.getOrPut(
                                    programmeChannel
                                ) {
                                    mutableListOf()
                                }.add(
                                    Programme(
                                        start,
                                        end,
                                        title.ifBlank {
                                            "Untitled programme"
                                        }
                                    )
                                )
                            }

                            inProgramme = false
                        }
                    }
                }

                event = parser.next()
            }
        }

        val candidates =
            mutableMapOf<String, MutableSet<String>>()

        aliases.forEach { (id, names) ->
            names.forEach { name ->
                if (name in wanted) {
                    candidates.getOrPut(name) {
                        mutableSetOf()
                    }.add(id)
                }
            }
        }

        val listings =
            mutableMapOf<String, Listing>()

        for (stream in streams) {
            val manualKey = manual[stream.id]

            val chosen = if (manualKey != null) {
                selected.values.firstOrNull {
                    it.key == manualKey
                }
            } else {
                candidates[
                    normalise(stream.title)
                ]?.singleOrNull()?.let {
                    selected[it]
                }
            }

            if (chosen != null) {
                listings[stream.id] = Listing(
                    chosen,
                    programmes[chosen.id].orEmpty()
                        .sortedBy { it.start },
                    manualKey != null
                )
            }
        }

        return Feed(channels, listings)
    }

    private fun load(
        activity: Activity,
        account: XtreamApi.Account,
        streams: List<XtreamApi.Stream>,
        force: Boolean,
        progress: (String) -> Unit
    ): Result {

        val manual = streams.mapNotNull { stream ->
            saved(
                activity,
                account,
                stream
            )?.let {
                stream.id to it
            }
        }.toMap()

        val channels = mutableListOf<Channel>()

        val listings =
            mutableMapOf<String, Listing>()

        val messages = mutableListOf<String>()

        for ((country, code) in countries) {
            progress("Loading $country...")

            try {
                val feed = parseFeed(
                    obtain(activity, code, force),
                    code,
                    country,
                    streams,
                    manual
                )

                channels.addAll(feed.channels)

                feed.listings.forEach { (id, listing) ->
                    val mapping = manual[id]

                    if (
                        (mapping == null && id !in listings) ||
                        mapping == listing.channel.key
                    ) {
                        listings[id] = listing
                    }
                }

                messages.add("$country: loaded")

            } catch (error: Exception) {
                messages.add(
                    "$country: ${error.message ?: "unavailable"}"
                )
            }
        }

        return Result(
            channels,
            listings,
            messages
        )
    }

    fun show(
        activity: Activity,
        account: XtreamApi.Account
    ) {

        val executor =
            Executors.newSingleThreadExecutor()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(activity, 14),
                dp(activity, 10),
                dp(activity, 14),
                dp(activity, 12)
            )

            setBackgroundColor(navy)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .create()

        fun text(
            value: String,
            size: Float = 15f,
            color: Int = Color.WHITE
        ) = TextView(activity).apply {
            this.text = value
            textSize = size
            setTextColor(color)

            setPadding(
                dp(activity, 5),
                dp(activity, 7),
                dp(activity, 5),
                dp(activity, 7)
            )
        }

        fun button(
            value: String,
            action: () -> Unit
        ) = Button(activity).apply {
            text = value
            isAllCaps = false
            isFocusable = true
            setOnClickListener {
                action()
            }
        }

        fun tile(
            value: String,
            action: () -> Unit
        ) = TextView(activity).apply {
            text = value
            textSize = 14f
            setTextColor(Color.WHITE)

            setPadding(
                dp(activity, 12),
                dp(activity, 13),
                dp(activity, 12),
                dp(activity, 13)
            )

            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true

            background = GradientDrawable().apply {
                setColor(panel)
                cornerRadius =
                    dp(activity, 8).toFloat()
            }

            setOnFocusChangeListener { _, focused ->
                background = GradientDrawable().apply {
                    setColor(
                        if (focused) {
                            Color.rgb(13, 104, 153)
                        } else {
                            panel
                        }
                    )

                    setStroke(
                        dp(activity, 2),
                        if (focused) cyan else navy
                    )

                    cornerRadius =
                        dp(activity, 8).toFloat()
                }
            }

            setOnClickListener {
                action()
            }
        }

        root.addView(
            text(
                "DREAMSTREAM • 7-DAY TV GUIDE",
                23f,
                cyan
            )
        )

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val loadButton = button("LOAD GUIDE") {}
        val refreshButton = button("REFRESH") {}
        val closeButton = button("CLOSE") {
            dialog.dismiss()
        }

        toolbar.addView(
            loadButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        toolbar.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        toolbar.addView(
            closeButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(toolbar)

        val status = text(
            "Load the combined UK, USA, Canada and NZ guide.",
            13f,
            muted
        )

        root.addView(status)

        val search = EditText(activity).apply {
            hint = "Search IPTV channels"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
        }

        root.addView(search)

        val dayRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        root.addView(dayRow)

        val scroll = ScrollView(activity).apply {
            fillViewport = true
        }

        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(rows)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        var streams = emptyList<XtreamApi.Stream>()
        var channels = emptyList<Channel>()
        var listings = emptyMap<String, Listing>()

        var day = 0
        var loading = false

        var reload: () -> Unit = {}
        var render: () -> Unit = {}

        fun chooseMapping(stream: XtreamApi.Stream) {

            if (channels.isEmpty()) {
                Toast.makeText(
                    activity,
                    "Load the guide first",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL

                setPadding(
                    dp(activity, 12),
                    dp(activity, 8),
                    dp(activity, 12),
                    dp(activity, 8)
                )
            }

            val input = EditText(activity).apply {
                hint = "Search EPG channel"
                setSingleLine(true)
            }

            val list = ListView(activity)

            layout.addView(input)

            layout.addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 300)
                )
            )

            val picker = AlertDialog.Builder(activity)
                .setTitle("Match ${stream.title}")
                .setView(layout)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton(
                    "RESET TO AUTOMATIC"
                ) { _, _ ->
                    save(
                        activity,
                        account,
                        stream,
                        null
                    )

                    reload()
                }
                .create()

            var shown = emptyList<Channel>()

            fun update() {
                val query =
                    input.text.toString().trim()

                shown = channels.filter {
                    query.isBlank() ||
                        it.name.contains(query, true) ||
                        it.id.contains(query, true) ||
                        it.country.contains(query, true)
                }.take(200)

                list.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_list_item_1,
                    shown.map {
                        "${it.name} (${it.country})"
                    }
                )
            }

            list.setOnItemClickListener {
                _, _, position, _ ->

                shown.getOrNull(position)?.let {
                    save(
                        activity,
                        account,
                        stream,
                        it
                    )

                    picker.dismiss()
                    reload()
                }
            }

            input.addTextChangedListener(
                object : TextWatcher {

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        update()
                    }

                    override fun afterTextChanged(
                        s: Editable?
                    ) {}
                }
            )

            update()
            picker.show()
        }

        fun details(
            stream: XtreamApi.Stream,
            listing: Listing,
            programme: Programme
        ) {

            val info =
                "${stream.title}\n" +
                "${listing.channel.country} • " +
                "${listing.channel.name}\n" +
                "${format(programme.start, "EEE d MMM HH:mm")}" +
                " – " +
                format(
                    programme.end,
                    "EEE d MMM HH:mm"
                ) +
                "\n\n${programme.title}"

            AlertDialog.Builder(activity)
                .setTitle("PROGRAMME DETAILS")
                .setMessage(info)
                .setPositiveButton(
                    "WATCH CHANNEL"
                ) { _, _ ->

                    activity.startActivity(
                        Intent(
                            activity,
                            PlayerActivity::class.java
                        ).putExtra(
                            "url",
                            XtreamApi.playbackUrl(
                                account,
                                stream
                            )
                        )
                    )
                }
                .setNegativeButton("CLOSE", null)
                .show()
        }

        fun updateDays() {
            dayRow.removeAllViews()

            for (index in 0..6) {

                val date = format(
                    dayStart(index),
                    "EEE d MMM"
                )

                val dayButton = button(
                    if (day == index) {
                        "● $date"
                    } else {
                        date
                    }
                ) {
                    day = index
                    updateDays()
                    render()
                }

                dayButton.textSize = 11f

                dayRow.addView(
                    dayButton,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
            }
        }

        render = {
            rows.removeAllViews()

            val query =
                search.text.toString().trim()

            val filtered = streams.filter {
                query.isBlank() ||
                    it.title.contains(query, true)
            }

            val start = dayStart(day)
            val end = dayStart(day + 1)
            val now = System.currentTimeMillis()

            val shown = filtered.take(40)

            for (stream in shown) {

                val listing = listings[stream.id]

                val header = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val title = text(
                    stream.title,
                    17f,
                    cyan
                ).apply {
                    setTypeface(null, Typeface.BOLD)
                }

                header.addView(
                    title,
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                val map = button(
                    if (listing?.manual == true) {
                        "EDIT MATCH"
                    } else {
                        "MATCH EPG"
                    }
                ) {
                    chooseMapping(stream)
                }

                header.addView(map)
                rows.addView(header)

                if (listing != null) {
                    rows.addView(
                        text(
                            "${listing.channel.country} • " +
                            "${if (listing.manual) "Manual" else "Automatic"}: " +
                            listing.channel.name,
                            12f,
                            muted
                        )
                    )
                }

                val horizontal =
                    HorizontalScrollView(activity).apply {
                        isHorizontalScrollBarEnabled = true

                        descendantFocusability =
                            ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }

                val timeline = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val programmes =
                    listing?.programmes.orEmpty().filter {
                        it.end > start &&
                            it.start < end
                    }

                if (programmes.isEmpty()) {
                    timeline.addView(
                        text(
                            "No programme information for this date",
                            14f,
                            muted
                        )
                    )
                } else {
                    for (programme in programmes) {

                        val begins = maxOf(
                            programme.start,
                            start
                        )

                        val finishes = minOf(
                            programme.end,
                            end
                        )

                        val minutes =
                            ((finishes - begins) / 60000L)
                                .coerceAtLeast(1L)

                        val width = dp(
                            activity,
                            (minutes * 3L)
                                .coerceIn(125L, 460L)
                                .toInt()
                        )

                        val label =
                            "${format(programme.start, "HH:mm")}" +
                            "–" +
                            "${format(programme.end, "HH:mm")}\n" +
                            programme.title +
                            if (
                                programme.start <= now &&
                                programme.end > now
                            ) {
                                "\n● NOW"
                            } else {
                                ""
                            }

                        val item = tile(label) {
                            details(
                                stream,
                                listing!!,
                                programme
                            )
                        }

                        val params =
                            LinearLayout.LayoutParams(
                                width,
                                dp(activity, 94)
                            ).apply {
                                rightMargin =
                                    dp(activity, 5)
                            }

                        timeline.addView(
                            item,
                            params
                        )
                    }
                }

                horizontal.addView(timeline)
                rows.addView(horizontal)

                rows.addView(
                    View(activity).apply {
                        setBackgroundColor(
                            Color.rgb(42, 57, 80)
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(activity, 1)
                    )
                )
            }

            if (filtered.size > 40) {
                rows.addView(
                    text(
                        "Showing 40 of ${filtered.size} channels. " +
                        "Search to narrow the list.",
                        13f,
                        muted
                    )
                )
            }
        }

        fun loadGuide(force: Boolean) {

            if (loading) return

            loading = true

            status.text =
                "Retrieving IPTV channels..."

            rows.removeAllViews()

            executor.execute {
                try {
                    val found = XtreamApi.streams(
                        account,
                        "live"
                    )

                    val guide = load(
                        activity,
                        account,
                        found,
                        force
                    ) { message ->

                        activity.runOnUiThread {
                            if (dialog.isShowing) {
                                status.text = message
                            }
                        }
                    }

                    activity.runOnUiThread {
                        loading = false

                        if (dialog.isShowing) {
                            streams = found
                            channels = guide.channels
                            listings = guide.listings

                            status.text =
                                "${listings.size} matched / " +
                                "${streams.size} IPTV channels\n" +
                                guide.messages.joinToString(" • ")

                            render()
                        }
                    }

                } catch (error: Exception) {
                    activity.runOnUiThread {
                        loading = false

                        if (dialog.isShowing) {
                            status.text =
                                error.message
                                    ?: "EPG loading failed"
                        }
                    }
                }
            }
        }

        reload = {
            loadGuide(false)
        }

        loadButton.setOnClickListener {
            loadGuide(false)
        }

        refreshButton.setOnClickListener {
            loadGuide(true)
        }

        search.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    render()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {}
            }
        )

        updateDays()

        dialog.setOnDismissListener {
            executor.shutdownNow()
        }

        dialog.show()

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        loadGuide(false)
    }
}
