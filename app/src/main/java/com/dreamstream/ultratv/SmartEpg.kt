package com.dreamstream.ultratv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.Xml
import android.view.ViewGroup
import android.widget.*
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

object SmartEpg {

    private const val BASE =
        "https://epgshare01.online/epgshare01/"

    private const val CACHE_MS =
        12L * 60L * 60L * 1000L

    private const val MAX_BYTES =
        20L * 1024L * 1024L

    private val countries = listOf(
        "United Kingdom" to "UK1",
        "United States" to "US2",
        "Canada" to "CA2",
        "New Zealand" to "NZ1"
    )

    private val navy = Color.rgb(9, 14, 30)
    private val cyan = Color.rgb(48, 207, 255)

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
        val key: String
            get() = "$code|$id"
    }

    private data class Listing(
        val channel: Channel,
        val programmes: List<Programme>,
        val manual: Boolean
    )

    private data class CountryData(
        val channels: List<Channel>,
        val listings: Map<String, Listing>
    )

    private data class Result(
        val channels: List<Channel>,
        val listings: Map<String, Listing>,
        val messages: List<String>
    )

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(
                Regex(
                    "\\b(uk|us|usa|ca|canada|nz|" +
                    "hd|fhd|uhd|4k|sd|hevc|h265)\\b"
                ),
                " "
            )
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
            }

    private fun preferences(activity: Activity) =
        activity.getSharedPreferences(
            "dreamstream_manual_epg",
            Activity.MODE_PRIVATE
        )

    private fun mappingKey(
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ): String = hash(
        account.server.url + "|" +
        account.username + "|" +
        stream.id
    )

    private fun savedMapping(
        activity: Activity,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ): String? = preferences(activity).getString(
        mappingKey(account, stream),
        null
    )

    private fun saveMapping(
        activity: Activity,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream,
        channel: Channel?
    ) {
        val editor = preferences(activity).edit()
        val key = mappingKey(account, stream)

        if (channel == null) {
            editor.remove(key)
        } else {
            editor.putString(key, channel.key)
        }

        editor.apply()
    }

    private fun parseDate(value: String): Long {
        val input = value.trim()

        val patterns = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmm Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm"
        )

        for (pattern in patterns) {
            try {
                val date = SimpleDateFormat(
                    pattern,
                    Locale.US
                ).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(input)

                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }

        return 0L
    }

    private fun clock(value: Long): String =
        SimpleDateFormat(
            "HH:mm",
            Locale.UK
        ).format(Date(value))

    private fun cacheFile(
        activity: Activity,
        code: String
    ): File = File(
        activity.cacheDir,
        "dreamstream_epg_$code.xml.gz"
    )

    private fun download(
        activity: Activity,
        code: String
    ): File {
        val target = cacheFile(activity, code)
        val temporary = File(
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
            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode}"
            }

            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        total += count
                        check(total <= MAX_BYTES) {
                            "Feed exceeds 20 MB"
                        }

                        output.write(buffer, 0, count)
                    }
                }
            }

            check(temporary.length() > 0) {
                "Empty EPG download"
            }

            GZIPInputStream(
                temporary.inputStream()
            ).use {
                check(it.read() != -1) {
                    "Invalid gzip feed"
                }
            }

            temporary.copyTo(
                target,
                overwrite = true
            )

            activity.getSharedPreferences(
                "dreamstream_epg_cache",
                Activity.MODE_PRIVATE
            ).edit()
                .putLong(code, System.currentTimeMillis())
                .apply()

            return target
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun obtain(
        activity: Activity,
        code: String,
        force: Boolean
    ): File {
        val target = cacheFile(activity, code)

        val updated = activity.getSharedPreferences(
            "dreamstream_epg_cache",
            Activity.MODE_PRIVATE
        ).getLong(code, 0L)

        val age = System.currentTimeMillis() - updated

        if (
            !force &&
            target.exists() &&
            age in 0 until CACHE_MS
        ) {
            return target
        }

        return try {
            download(activity, code)
        } catch (error: Exception) {
            if (target.exists()) target
            else throw error
        }
    }

    private fun parseCountry(
        file: File,
        code: String,
        country: String,
        streams: List<XtreamApi.Stream>,
        manual: Map<String, String>
    ): CountryData {

        val wanted = streams.groupBy {
            normalise(it.title)
        }.filterKeys { it.isNotBlank() }

        val channels = mutableListOf<Channel>()
        val aliases =
            mutableMapOf<String, MutableSet<String>>()

        val selected =
            mutableMapOf<String, Channel>()

        val programmes =
            mutableMapOf<String, MutableList<Programme>>()

        val now = System.currentTimeMillis()
        val limit =
            now + 7L * 24L * 60L * 60L * 1000L

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

            fun finishChannel() {
                if (channelId.isBlank()) return

                val channel = Channel(
                    code,
                    country,
                    channelId,
                    channelName.ifBlank { channelId }
                )

                channels.add(channel)

                val keys = aliases.getOrPut(channelId) {
                    mutableSetOf()
                }

                keys.add(normalise(channelId))
                keys.add(normalise(channel.name))

                val automatic = keys.any {
                    wanted.containsKey(it)
                }

                val manualMatch = manual.values.any {
                    it == channel.key
                }

                if (automatic || manualMatch) {
                    selected[channelId] = channel
                }
            }

            while (event != XmlPullParser.END_DOCUMENT) {

                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            inChannel = true

                            channelId =
                                parser.getAttributeValue(
                                    null, "id"
                                ).orEmpty()

                            channelName = ""
                        }

                        "display-name" -> {
                            if (inChannel) {
                                val name = parser.nextText()

                                if (channelName.isBlank()) {
                                    channelName = name
                                }

                                aliases.getOrPut(channelId) {
                                    mutableSetOf()
                                }.add(normalise(name))
                            }
                        }

                        "programme" -> {
                            inProgramme = true

                            programmeChannel =
                                parser.getAttributeValue(
                                    null, "channel"
                                ).orEmpty()

                            start = parseDate(
                                parser.getAttributeValue(
                                    null, "start"
                                ).orEmpty()
                            )

                            end = parseDate(
                                parser.getAttributeValue(
                                    null, "stop"
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

                if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            finishChannel()
                            inChannel = false
                        }

                        "programme" -> {
                            if (
                                inProgramme &&
                                selected.containsKey(
                                    programmeChannel
                                ) &&
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

        aliases.forEach { (id, keys) ->
            keys.forEach { key ->
                if (wanted.containsKey(key)) {
                    candidates.getOrPut(key) {
                        mutableSetOf()
                    }.add(id)
                }
            }
        }

        val listings = mutableMapOf<String, Listing>()

        for (stream in streams) {
            val manualKey = manual[stream.id]

            val chosen = if (manualKey != null) {
                selected.values.firstOrNull {
                    it.key == manualKey
                }
            } else {
                val ids = candidates[
                    normalise(stream.title)
                ].orEmpty()

                if (ids.size == 1) {
                    selected[ids.first()]
                } else {
                    null
                }
            }

            if (chosen != null) {
                val entries =
                    programmes[chosen.id].orEmpty()
                        .sortedBy { it.start }

                listings[stream.id] = Listing(
                    chosen,
                    entries,
                    manualKey != null
                )
            }
        }

        return CountryData(channels, listings)
    }

    private fun loadCombined(
        activity: Activity,
        account: XtreamApi.Account,
        streams: List<XtreamApi.Stream>,
        force: Boolean,
        progress: (String) -> Unit
    ): Result {

        val manual = streams.mapNotNull { stream ->
            val value = savedMapping(
                activity,
                account,
                stream
            )

            if (value == null) null
            else stream.id to value
        }.toMap()

        val combined =
            mutableMapOf<String, Listing>()

        val allChannels =
            mutableListOf<Channel>()

        val messages =
            mutableListOf<String>()

        for ((country, code) in countries) {

            progress("Loading $country...")

            try {
                val file = obtain(activity, code, force)

                val data = parseCountry(
                    file,
                    code,
                    country,
                    streams,
                    manual
                )

                allChannels.addAll(data.channels)

                data.listings.forEach { (streamId, listing) ->
                    val saved = manual[streamId]

                    if (
                        saved == null &&
                        !combined.containsKey(streamId)
                    ) {
                        combined[streamId] = listing
                    }

                    if (
                        saved != null &&
                        listing.channel.key == saved
                    ) {
                        combined[streamId] = listing
                    }
                }

                messages.add("$country: loaded")

            } catch (_: Exception) {
                messages.add("$country: unavailable")
            }
        }

        return Result(
            allChannels,
            combined,
            messages
        )
    }

    fun show(
        activity: Activity,
        account: XtreamApi.Account
    ) {

        val executor =
            Executors.newSingleThreadExecutor()

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(navy)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
        }

        scroll.addView(root)

        fun label(
            value: String,
            size: Float = 17f
        ): TextView =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(Color.WHITE)
                setPadding(8, 12, 8, 12)
            }

        fun button(
            value: String,
            action: () -> Unit
        ): Button =
            Button(activity).apply {
                text = value
                isAllCaps = false
                isFocusable = true
                setOnClickListener { action() }
            }

        val dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .create()

        root.addView(
            label(
                "DREAMSTREAM SMART EPG",
                26f
            ).apply {
                setTextColor(cyan)
            }
        )

        root.addView(
            label(
                "UK • USA • Canada • New Zealand\n" +
                "Automatic and manual channel matching",
                15f
            )
        )

        val status = label(
            "Load the combined guide to begin.",
            15f
        )

        root.addView(status)

        val search = EditText(activity).apply {
            hint = "Search IPTV channels"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        root.addView(search)

        val results = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        var streams = emptyList<XtreamApi.Stream>()
        var channels = emptyList<Channel>()
        var listings = emptyMap<String, Listing>()
        var loading = false

        // Corrected callback declaration.
        // Defined before render() and chooseMapping().
        var reloadAction: () -> Unit = {}

        fun chooseMapping(
            stream: XtreamApi.Stream,
            onSaved: () -> Unit
        ) {
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
                setPadding(18, 12, 18, 12)
            }

            val input = EditText(activity).apply {
                hint = "Search EPG channel name"
                setSingleLine(true)
            }

            val channelList = ListView(activity)

            layout.addView(input)

            layout.addView(
                channelList,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    420
                )
            )

            val picker = AlertDialog.Builder(activity)
                .setTitle("Match: ${stream.title}")
                .setView(layout)
                .setNegativeButton("CANCEL", null)
                .setNeutralButton(
                    "RESET TO AUTOMATIC"
                ) { _, _ ->
                    saveMapping(
                        activity,
                        account,
                        stream,
                        null
                    )

                    onSaved()
                }
                .create()

            var shown = emptyList<Channel>()

            fun updateChoices() {
                val query =
                    input.text.toString().trim()

                shown = channels.filter {
                    query.isBlank() ||
                        it.name.contains(
                            query,
                            ignoreCase = true
                        ) ||
                        it.id.contains(
                            query,
                            ignoreCase = true
                        ) ||
                        it.country.contains(
                            query,
                            ignoreCase = true
                        )
                }.take(200)

                channelList.adapter = ArrayAdapter(
                    activity,
                    android.R.layout.simple_list_item_1,
                    shown.map {
                        "${it.name} (${it.country})"
                    }
                )
            }

            channelList.setOnItemClickListener {
                _, _, position, _ ->

                val chosen = shown.getOrNull(position)
                    ?: return@setOnItemClickListener

                saveMapping(
                    activity,
                    account,
                    stream,
                    chosen
                )

                picker.dismiss()
                onSaved()
            }

            input.addTextChangedListener(
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
                        updateChoices()
                    }

                    override fun afterTextChanged(
                        s: Editable?
                    ) = Unit
                }
            )

            updateChoices()
            picker.show()
        }

        fun render() {
            results.removeAllViews()

            val query =
                search.text.toString().trim()

            val filtered = streams.filter {
                query.isBlank() ||
                    it.title.contains(
                        query,
                        ignoreCase = true
                    )
            }

            val now = System.currentTimeMillis()

            for (stream in filtered.take(100)) {
                val listing = listings[stream.id]
                val entries =
                    listing?.programmes.orEmpty()

                val current = entries.firstOrNull {
                    it.start <= now &&
                        it.end > now
                }

                val next = entries.firstOrNull {
                    it.start > now
                }

                results.addView(
                    label(stream.title, 19f).apply {
                        setTextColor(cyan)
                    }
                )

                results.addView(
                    label(
                        when {
                            listing == null ->
                                "EPG: Not matched"

                            listing.manual ->
                                "EPG: Manual • " +
                                listing.channel.name +
                                " (${listing.channel.country})"

                            else ->
                                "EPG: Automatic • " +
                                listing.channel.name +
                                " (${listing.channel.country})"
                        },
                        13f
                    )
                )

                results.addView(
                    label(
                        if (current != null) {
                            "NOW  ${clock(current.start)}-" +
                                "${clock(current.end)}  " +
                                current.title
                        } else {
                            "NOW  No programme information"
                        },
                        15f
                    )
                )

                results.addView(
                    label(
                        if (next != null) {
                            "NEXT  ${clock(next.start)}  " +
                                next.title
                        } else {
                            "NEXT  No programme information"
                        },
                        15f
                    )
                )

                results.addView(
                    button("WATCH") {
                        val url =
                            XtreamApi.playbackUrl(
                                account,
                                stream
                            )

                        activity.startActivity(
                            Intent(
                                activity,
                                PlayerActivity::class.java
                            ).putExtra(
                                "url",
                                url
                            )
                        )
                    }
                )

                // Corrected: invokes reloadAction.
                results.addView(
                    button("MATCH EPG CHANNEL") {
                        chooseMapping(stream) {
                            reloadAction()
                        }
                    }
                )
            }

            if (filtered.size > 100) {
                results.addView(
                    label(
                        "Showing 100 of ${filtered.size}. " +
                        "Search to narrow results.",
                        14f
                    )
                )
            }
        }

        fun load(force: Boolean) {
            if (loading) return

            loading = true
            status.text = "Loading IPTV channels..."
            results.removeAllViews()

            executor.execute {
                try {
                    val loadedStreams =
                        XtreamApi.streams(
                            account,
                            "live"
                        )

                    val guide = loadCombined(
                        activity,
                        account,
                        loadedStreams,
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

                        if (!dialog.isShowing) {
                            return@runOnUiThread
                        }

                        streams = loadedStreams
                        channels = guide.channels
                        listings = guide.listings

                        status.text =
                            "${listings.size} matched of " +
                            "${streams.size} channels\n" +
                            guide.messages.joinToString("\n")

                        render()
                    }

                } catch (error: Exception) {
                    activity.runOnUiThread {
                        loading = false

                        if (dialog.isShowing) {
                            status.text =
                                error.message
                                    ?: "Guide loading failed"
                        }
                    }
                }
            }
        }

        // Corrected: callback now points to load().
        reloadAction = {
            load(false)
        }

        root.addView(
            button("LOAD COMBINED GUIDE") {
                load(false)
            }
        )

        root.addView(
            button("REFRESH ALL FOUR COUNTRIES") {
                load(true)
            }
        )

        root.addView(
            button("CLOSE") {
                dialog.dismiss()
            }
        )

        root.addView(results)

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
                    render()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        dialog.setOnDismissListener {
            executor.shutdownNow()
        }

        dialog.show()

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
