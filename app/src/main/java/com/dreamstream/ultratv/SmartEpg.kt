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

    private data class Listing(
        val country: String,
        val programmes: List<Programme>
    )

    private data class Result(
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
            .replace(
                Regex("\\s+"),
                " "
            )

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
                val parser = SimpleDateFormat(
                    pattern,
                    Locale.US
                ).apply {
                    isLenient = false
                    timeZone =
                        TimeZone.getTimeZone("UTC")
                }

                val date = parser.parse(input)

                if (date != null) {
                    return date.time
                }
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

        val url =
            "${BASE}epg_ripper_${code}.xml.gz"

        val connection =
            URL(url).openConnection()
                as HttpURLConnection

        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true

        try {
            check(
                connection.responseCode in 200..299
            ) {
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

                        output.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }

            check(temporary.length() > 0) {
                "Empty EPG download"
            }

            // Check gzip before replacing cache.
            GZIPInputStream(
                temporary.inputStream()
            ).use { input ->
                check(input.read() != -1) {
                    "Invalid or empty gzip feed"
                }
            }

            // Preserve the previous cache until
            // the new file has been downloaded.
            if (!temporary.renameTo(target)) {
                temporary.copyTo(
                    target,
                    overwrite = true
                )
            }

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

        val age =
            System.currentTimeMillis() - updated

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
            if (target.exists()) {
                target
            } else {
                throw error
            }
        }
    }

    /*
     * Parse one country at a time.
     *
     * Only retain programmes for channels
     * matching the user's IPTV catalogue.
     *
     * This avoids keeping all four complete
     * XMLTV guides in device memory.
     */
    private fun parseCountry(
        file: File,
        streams: List<XtreamApi.Stream>,
        country: String
    ): Map<String, Listing> {

        val wanted = streams.groupBy {
            normalise(it.title)
        }.filterKeys {
            it.isNotBlank()
        }

        val channelMatches =
            mutableMapOf<String, MutableSet<String>>()

        val programmes =
            mutableMapOf<String, MutableList<Programme>>()

        val now = System.currentTimeMillis()

        val limit =
            now + 7L * 24L * 60L * 60L * 1000L

        GZIPInputStream(
            file.inputStream().buffered()
        ).buffered().use { input ->

            val parser = Xml.newPullParser()

            parser.setFeature(
                XmlPullParser.FEATURE_PROCESS_NAMESPACES,
                false
            )

            parser.setInput(input, "UTF-8")

            var event = parser.eventType

            var inChannel = false
            var inProgramme = false

            var channelId = ""
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

                            val key =
                                normalise(channelId)

                            if (wanted.containsKey(key)) {
                                channelMatches.getOrPut(
                                    channelId
                                ) {
                                    mutableSetOf()
                                }.add(key)
                            }
                        }

                        "display-name" -> {
                            if (inChannel) {
                                val name =
                                    parser.nextText()

                                val key =
                                    normalise(name)

                                if (
                                    wanted.containsKey(key)
                                ) {
                                    channelMatches.getOrPut(
                                        channelId
                                    ) {
                                        mutableSetOf()
                                    }.add(key)
                                }
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
                            inChannel = false
                        }

                        "programme" -> {
                            if (
                                inProgramme &&
                                channelMatches.containsKey(
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

        channelMatches.forEach {
            (id, keys) ->

            keys.forEach { key ->
                candidates.getOrPut(key) {
                    mutableSetOf()
                }.add(id)
            }
        }

        val result =
            mutableMapOf<String, Listing>()

        wanted.forEach { (key, matchingStreams) ->

            val ids =
                candidates[key].orEmpty()

            // Do not guess between ambiguous
            // channels with the same name.
            if (ids.size == 1) {

                val id = ids.first()

                val entries =
                    programmes[id].orEmpty()
                        .sortedBy { it.start }

                if (entries.isNotEmpty()) {

                    matchingStreams.forEach { stream ->
                        result[stream.id] = Listing(
                            country,
                            entries
                        )
                    }
                }
            }
        }

        return result
    }

    private fun loadCombined(
        activity: Activity,
        streams: List<XtreamApi.Stream>,
        force: Boolean,
        progress: (String) -> Unit
    ): Result {

        val combined =
            mutableMapOf<String, Listing>()

        val messages =
            mutableListOf<String>()

        for ((country, code) in countries) {

            progress("Loading $country...")

            try {
                val file = obtain(
                    activity,
                    code,
                    force
                )

                val listings = parseCountry(
                    file,
                    streams,
                    country
                )

                // UK is first. Later countries
                // fill gaps but cannot replace
                // an earlier successful match.
                listings.forEach { (id, listing) ->
                    if (!combined.containsKey(id)) {
                        combined[id] = listing
                    }
                }

                messages.add(
                    "$country: ${listings.size} matches"
                )

            } catch (error: Exception) {
                messages.add(
                    "$country: unavailable"
                )
            }
        }

        return Result(
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
                setOnClickListener {
                    action()
                }
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
                "COMBINED GUIDE\n" +
                "United Kingdom • USA • Canada • New Zealand",
                16f
            )
        )

        val status = label(
            "Press Load Combined Guide to begin.",
            15f
        )

        root.addView(status)

        val search = EditText(activity).apply {
            hint = "Search TV channels"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        root.addView(search)

        val results = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        var streams =
            emptyList<XtreamApi.Stream>()

        var combined =
            emptyMap<String, Listing>()

        var loading = false

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

            val now =
                System.currentTimeMillis()

            val displayed =
                filtered.take(100)

            for (stream in displayed) {

                val listing =
                    combined[stream.id]

                val entries =
                    listing?.programmes.orEmpty()

                val current =
                    entries.firstOrNull {
                        it.start <= now &&
                            it.end > now
                    }

                val next =
                    entries.firstOrNull {
                        it.start > now
                    }

                results.addView(
                    label(
                        stream.title,
                        19f
                    ).apply {
                        setTextColor(cyan)
                    }
                )

                if (listing != null) {
                    results.addView(
                        label(
                            "EPG: ${listing.country}",
                            13f
                        ).apply {
                            setTextColor(Color.LTGRAY)
                        }
                    )
                }

                results.addView(
                    label(
                        if (current != null) {
                            "NOW  ${clock(current.start)}–" +
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
                    button("WATCH CHANNEL") {
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
            }

            if (filtered.size > 100) {
                results.addView(
                    label(
                        "Showing 100 of ${filtered.size} channels. " +
                        "Use Search to narrow the list.",
                        14f
                    )
                )
            }
        }

        fun load(force: Boolean) {

            if (loading) return

            loading = true

            status.text =
                "Retrieving Live TV channels..."

            results.removeAllViews()

            executor.execute {
                try {
                    val loadedStreams =
                        XtreamApi.streams(
                            account,
                            "live"
                        )

                    val loadedGuide =
                        loadCombined(
                            activity,
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
                        combined = loadedGuide.listings

                        status.text =
                            "${combined.size} matched channels " +
                            "from ${streams.size} IPTV channels.\n" +
                            loadedGuide.messages.joinToString(
                                "\n"
                            )

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
