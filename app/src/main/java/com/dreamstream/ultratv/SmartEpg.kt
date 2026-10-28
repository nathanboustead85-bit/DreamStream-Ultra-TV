package com.dreamstream.ultratv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import android.util.Xml
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

    private const val MAX_DOWNLOAD =
        20L * 1024L * 1024L

    private val countries = listOf(
        "United Kingdom" to "UK1",
        "United States" to "US2",
        "Canada" to "CA2",
        "New Zealand" to "NZ1"
    )

    private data class Programme(
        val channel: String,
        val start: Long,
        val end: Long,
        val title: String
    )

    private data class Guide(
        val aliases: Map<String, List<String>>,
        val programmes: Map<String, List<Programme>>
    )

    private fun feedUrl(code: String): String =
        "${BASE}epg_ripper_${code}.xml.gz"

    private fun cacheFile(
        activity: Activity,
        code: String
    ): File = File(
        activity.cacheDir,
        "dreamstream_epg_$code.xml.gz"
    )

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(
                Regex("\\b(uk|us|ca|nz|hd|fhd|uhd|4k|sd|hevc)\\b"),
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

        val formats = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmm Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm"
        )

        for (format in formats) {
            try {
                val date = SimpleDateFormat(
                    format,
                    Locale.US
                ).apply {
                    isLenient = false
                    timeZone =
                        TimeZone.getTimeZone("UTC")
                }.parse(input)

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

    private fun download(
        activity: Activity,
        code: String
    ): File {

        val target = cacheFile(activity, code)

        val temporary = File(
            activity.cacheDir,
            "dreamstream_epg_$code.tmp"
        )

        val connection =
            URL(feedUrl(code)).openConnection()
                as HttpURLConnection

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
                temporary.outputStream().use { output ->

                    val buffer = ByteArray(8192)
                    var total = 0L

                    while (true) {
                        val count = input.read(buffer)

                        if (count < 0) break

                        total += count

                        check(total <= MAX_DOWNLOAD) {
                            "EPG download exceeds 20 MB"
                        }

                        output.write(
                            buffer,
                            0,
                            count
                        )
                    }
                }
            }

            check(temporary.length() > 0L) {
                "Downloaded EPG is empty"
            }

            // Keep the previous cache if the new
            // download fails before this point.
            temporary.copyTo(
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

    private fun parse(file: File): Guide {

        val aliases =
            mutableMapOf<String, MutableList<String>>()

        val programmes =
            mutableMapOf<String, MutableList<Programme>>()

        val now = System.currentTimeMillis()

        val maximum =
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

            var channelId = ""
            var inChannel = false
            var inProgramme = false

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

                            aliases.getOrPut(
                                channelId
                            ) {
                                mutableListOf()
                            }
                        }

                        "display-name" -> {
                            if (inChannel) {
                                val name = parser.nextText()

                                if (name.isNotBlank()) {
                                    aliases.getOrPut(
                                        channelId
                                    ) {
                                        mutableListOf()
                                    }.add(name)
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
                                end > now &&
                                start < maximum &&
                                end > start
                            ) {
                                programmes.getOrPut(
                                    programmeChannel
                                ) {
                                    mutableListOf()
                                }.add(
                                    Programme(
                                        programmeChannel,
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

        return Guide(
            aliases = aliases,
            programmes = programmes.mapValues {
                entry ->
                entry.value.sortedBy {
                    it.start
                }
            }
        )
    }

    private fun matchChannel(
        title: String,
        guide: Guide
    ): String? {

        val target = normalise(title)

        if (target.isBlank()) return null

        val matches = guide.aliases.filter {
            (id, names) ->

            normalise(id) == target ||
                names.any {
                    normalise(it) == target
                }
        }

        return if (matches.size == 1) {
            matches.keys.first()
        } else {
            null
        }
    }

    fun show(
        activity: Activity,
        account: XtreamApi.Account
    ) {

        val executor =
            Executors.newSingleThreadExecutor()

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(
                Color.rgb(9, 14, 30)
            )
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
            click: () -> Unit
        ): Button =
            Button(activity).apply {
                text = value
                isAllCaps = false
                isFocusable = true
                setOnClickListener {
                    click()
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
                setTextColor(
                    Color.rgb(48, 207, 255)
                )
            }
        )

        root.addView(
            label(
                "Independent TV guide - UK, USA, Canada and New Zealand",
                15f
            )
        )

        val spinner = Spinner(activity)

        spinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            countries.map { it.first }
        )

        val settings = activity.getSharedPreferences(
            "dreamstream_epg_settings",
            Activity.MODE_PRIVATE
        )

        spinner.setSelection(
            settings.getInt(
                "country",
                0
            ).coerceIn(
                0,
                countries.lastIndex
            )
        )

        root.addView(spinner)

        val status = label(
            "Select a country and load its guide.",
            15f
        )

        root.addView(status)

        val results = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun load(force: Boolean) {

            val index =
                spinner.selectedItemPosition.coerceIn(
                    0,
                    countries.lastIndex
                )

            val country = countries[index]

            settings.edit()
                .putInt(
                    "country",
                    index
                )
                .apply()

            status.text =
                "Loading ${country.first} guide..."

            results.removeAllViews()

            executor.execute {
                try {
                    val file = obtain(
                        activity,
                        country.second,
                        force
                    )

                    val guide = parse(file)

                    val streams = XtreamApi.streams(
                        account,
                        "live"
                    )

                    val now =
                        System.currentTimeMillis()

                    activity.runOnUiThread {

                        if (!dialog.isShowing) {
                            return@runOnUiThread
                        }

                        results.removeAllViews()

                        var matched = 0

                        streams.take(250).forEach {
                            stream ->

                            val channelId =
                                matchChannel(
                                    stream.title,
                                    guide
                                )

                            if (channelId != null) {
                                matched++
                            }

                            val entries =
                                guide.programmes[
                                    channelId
                                ].orEmpty()

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
                                    setTextColor(
                                        Color.rgb(
                                            48,
                                            207,
                                            255
                                        )
                                    )
                                }
                            )

                            results.addView(
                                label(
                                    if (current != null) {
                                        "NOW: " +
                                            current.title +
                                            " (" +
                                            clock(current.start) +
                                            "-" +
                                            clock(current.end) +
                                            ")"
                                    } else {
                                        "NOW: No programme information"
                                    },
                                    15f
                                )
                            )

                            results.addView(
                                label(
                                    if (next != null) {
                                        "NEXT: " +
                                            next.title +
                                            " (" +
                                            clock(next.start) +
                                            ")"
                                    } else {
                                        "NEXT: No programme information"
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
                        }

                        status.text =
                            "${country.first}: " +
                            "$matched matched channels " +
                            "out of ${streams.take(250).size} shown"
                    }

                } catch (error: Exception) {
                    activity.runOnUiThread {
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
            button("LOAD COUNTRY GUIDE") {
                load(false)
            }
        )

        root.addView(
            button("REFRESH GUIDE") {
                load(true)
            }
        )

        root.addView(
            button("CLOSE") {
                dialog.dismiss()
            }
        )

        root.addView(results)

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
