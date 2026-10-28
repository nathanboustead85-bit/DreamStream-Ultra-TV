package com.dreamstream.ultratv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object XtreamApi {

    data class Server(
        val name: String,
        val url: String
    )

    val servers = listOf(
        Server(
            "Dino",
            "http://line.dndnscloud.ru/"
        ),
        Server(
            "Lion",
            "http://vpn.liondnscloud.ru:80"
        ),
        Server(
            "Eagle",
            "http://vpn-esxtrapro.info:80"
        )
    )

    data class Account(
        val server: Server,
        val username: String,
        val password: String
    )

    data class Stream(
        val id: String,
        val title: String,
        val type: String,
        val extension: String,
        val categoryId: String
    )

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun base(account: Account): String =
        account.server.url.trimEnd('/')

    private fun api(
        account: Account,
        action: String = ""
    ): String {
        val url = "${base(account)}/player_api.php" +
            "?username=${enc(account.username)}" +
            "&password=${enc(account.password)}"

        return if (action.isEmpty()) {
            url
        } else {
            "$url&action=$action"
        }
    }

    private fun get(url: String): String {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        connection.connectTimeout = 12000
        connection.readTimeout = 16000
        connection.instanceFollowRedirects = true

        try {
            if (connection.responseCode !in 200..299) {
                error(
                    "Server HTTP ${connection.responseCode}"
                )
            }

            return connection.inputStream
                .bufferedReader()
                .use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun authenticate(account: Account) {
        val response = JSONObject(
            get(api(account))
        )

        val info = response.optJSONObject("user_info")
            ?: error("Invalid Xtream response")

        if (info.optInt("auth", 0) != 1) {
            error(
                "Login rejected. Check username and password."
            )
        }
    }

    fun categories(
        account: Account,
        type: String
    ): List<Pair<String, String>> {
        val action = when (type) {
            "live" -> "get_live_categories"
            "movie" -> "get_vod_categories"
            else -> "get_series_categories"
        }

        val json = JSONArray(
            get(api(account, action))
        )

        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index)
                ?: return@mapNotNull null

            item.optString("category_id") to
                item.optString(
                    "category_name",
                    "Other"
                )
        }
    }

    fun streams(
        account: Account,
        type: String
    ): List<Stream> {
        val action = when (type) {
            "live" -> "get_live_streams"
            "movie" -> "get_vod_streams"
            else -> "get_series"
        }

        val json = JSONArray(
            get(api(account, action))
        )

        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index)
                ?: return@mapNotNull null

            val id = if (type == "series") {
                item.optString("series_id")
            } else {
                item.optString("stream_id")
            }

            if (id.isBlank()) {
                return@mapNotNull null
            }

            Stream(
                id = id,
                title = item.optString(
                    "name",
                    "Untitled"
                ),
                type = type,
                extension = item.optString(
                    "container_extension",
                    "mp4"
                ),
                categoryId = item.optString(
                    "category_id"
                )
            )
        }
    }

    fun seriesEpisodes(
        account: Account,
        seriesId: String
    ): List<Stream> {
        val response = JSONObject(
            get(
                api(
                    account,
                    "get_series_info&series_id=${enc(seriesId)}"
                )
            )
        )

        val episodes = response.optJSONObject("episodes")
            ?: return emptyList()

        val result = mutableListOf<Stream>()
        val seasons = episodes.keys()

        while (seasons.hasNext()) {
            val season = seasons.next()
            val items = episodes.optJSONArray(season)
                ?: continue

            for (index in 0 until items.length()) {
                val episode = items.optJSONObject(index)
                    ?: continue

                val id = episode.optString("id")

                if (id.isNotBlank()) {
                    result.add(
                        Stream(
                            id = id,
                            title = "S$season - " +
                                episode.optString(
                                    "title",
                                    "Episode ${index + 1}"
                                ),
                            type = "episode",
                            extension = episode.optString(
                                "container_extension",
                                "mp4"
                            ),
                            categoryId = ""
                        )
                    )
                }
            }
        }

        return result
    }

    fun playbackUrl(
        account: Account,
        stream: Stream
    ): String {
        val path = when (stream.type) {
            "live" -> "live"
            "movie" -> "movie"
            else -> "series"
        }

        val extension = if (stream.type == "live") {
            "m3u8"
        } else {
            stream.extension.ifBlank { "mp4" }
        }

        return "${base(account)}/$path/" +
            "${enc(account.username)}/" +
            "${enc(account.password)}/" +
            "${enc(stream.id)}.$extension"
    }
}
