package com.dreamstream.ultratv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object RecentChannelsStore {

    private const val PREFS = "dreamstream_tv_recent"
    private const val MAX_RECENT = 20

    private fun key(
        account: XtreamApi.Account
    ): String {

        val identity =
            "${account.server.url}|${account.username}"

        return MessageDigest.getInstance("SHA-256")
            .digest(
                identity.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(it)
            }
    }

    // GET RECENTLY WATCHED CHANNELS

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

            val json = JSONArray(raw)

            (0 until json.length()).mapNotNull {
                index ->

                val item =
                    json.optJSONObject(index)
                        ?: return@mapNotNull null

                val id =
                    item.optString("id")

                if (id.isBlank()) {
                    return@mapNotNull null
                }

                XtreamApi.Stream(
                    id = id,
                    title = item.optString("title"),
                    type = "live",
                    extension = "m3u8",
                    categoryId = item.optString(
                        "categoryId"
                    )
                )
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    // ADD A CHANNEL TO RECENT HISTORY

    fun add(
        context: Context,
        account: XtreamApi.Account,
        stream: XtreamApi.Stream
    ) {

        if (stream.type != "live") {
            return
        }

        val updated =
            mutableListOf(stream)

        updated.addAll(
            list(
                context,
                account
            ).filter {
                it.id != stream.id
            }
        )

        val json = JSONArray()

        updated.take(
            MAX_RECENT
        ).forEach { item ->

            json.put(
                JSONObject().apply {
                    put(
                        "id",
                        item.id
                    )

                    put(
                        "title",
                        item.title
                    )

                    put(
                        "categoryId",
                        item.categoryId
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

    // NEW: CLEAR RECENTLY WATCHED CHANNELS

    fun clear(
        context: Context,
        account: XtreamApi.Account
    ) {

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(
                key(account)
            )
            .apply()
    }
}
