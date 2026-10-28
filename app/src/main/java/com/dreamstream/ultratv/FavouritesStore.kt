package com.dreamstream.ultratv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Stores Live TV, Movie and Series favourites
 * separately for each provider account.
 */
object FavouritesStore {

    private const val PREFS =
        "dreamstream_tv_favourites"

    private fun key(
        account: XtreamApi.Account
    ): String {

        val identity =
            "${account.server.url}|${account.username}"

        val digest = MessageDigest.getInstance(
            "SHA-256"
        ).digest(
            identity.toByteArray(Charsets.UTF_8)
        )

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

    fun list(
        context: Context,
        account: XtreamApi.Account
    ): List<XtreamApi.Stream> {

        val raw = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(
            key(account),
            "[]"
        ) ?: "[]"

        return try {
            val array = JSONArray(raw)

            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index)
                    ?: return@mapNotNull null

                val id = item.optString("id")
                val type = item.optString("type")

                if (
                    id.isBlank() ||
                    type !in listOf(
                        "live",
                        "movie",
                        "series"
                    )
                ) {
                    null
                } else {
                    XtreamApi.Stream(
                        id = id,
                        title = item.optString(
                            "title",
                            "Untitled"
                        ),
                        type = type,
                        extension = item.optString(
                            "extension",
                            "mp4"
                        ),
                        categoryId = item.optString(
                            "categoryId"
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun contains(
        context: Context,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ): Boolean {

        return list(
            context,
            account
        ).any {
            it.id == stream.id &&
                it.type == stream.type
        }
    }

    fun toggle(
        context: Context,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ) {

        val entries = list(
            context,
            account
        ).toMutableList()

        val existing = entries.indexOfFirst {
            it.id == stream.id &&
                it.type == stream.type
        }

        if (existing >= 0) {
            entries.removeAt(existing)
        } else {
            entries.add(stream)
        }

        val json = JSONArray()

        entries.forEach { entry ->
            json.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("title", entry.title)
                    put("type", entry.type)
                    put(
                        "extension",
                        entry.extension
                    )
                    put(
                        "categoryId",
                        entry.categoryId
                    )
                }
            )
        }

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .putString(
                key(account),
                json.toString()
            )
            .apply()
    }
}
