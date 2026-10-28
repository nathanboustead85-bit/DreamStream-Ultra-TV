package com.dreamstream.ultratv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private val navy = Color.rgb(9, 14, 30)
    private val cyan = Color.rgb(48, 207, 255)
    private val panel = Color.rgb(23, 34, 57)

    private val settings by lazy {
        getSharedPreferences(
            "dreamstream_tv_settings",
            MODE_PRIVATE
        )
    }

    private lateinit var root: LinearLayout

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f)
            .toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSettings()
    }

    private fun label(
        value: String,
        size: Float = 17f,
        colour: Int = Color.WHITE
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(colour)
        setPadding(
            dp(12), dp(10),
            dp(12), dp(10)
        )
    }

    private fun button(
        value: String,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 17f
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun section(title: String) {
        root.addView(
            label(title, 20f, cyan).apply {
                setTypeface(null, Typeface.BOLD)
            }
        )
    }

    private fun showSettings() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(navy)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(22), dp(18),
                dp(22), dp(30)
            )
        }

        scroll.addView(root)
        setContentView(scroll)

        root.addView(
            label(
                "DREAMSTREAM • SETTINGS",
                26f,
                cyan
            ).apply {
                setTypeface(null, Typeface.BOLD)
            }
        )

        root.addView(
            button("← BACK TO HOME") {
                finish()
            }
        )

        section("PLAYBACK")

        val autoControls = CheckBox(this).apply {
            text = "Show playback controls automatically"
            textSize = 17f
            setTextColor(Color.WHITE)
            isFocusable = true

            isChecked = settings.getBoolean(
                "auto_controls",
                true
            )

            setOnCheckedChangeListener { _, checked ->
                settings.edit()
                    .putBoolean(
                        "auto_controls",
                        checked
                    )
                    .apply()
            }
        }

        root.addView(autoControls)

        root.addView(
            label(
                "Choose how long playback controls stay visible.",
                15f
            )
        )

        val timeoutOptions = intArrayOf(3, 5, 10)

        val timeoutButtons =
            mutableListOf<Button>()

        fun updateTimeoutButtons() {
            val current = settings.getInt(
                "controller_timeout",
                5
            )

            timeoutButtons.forEachIndexed { index, item ->
                val selected =
                    timeoutOptions[index] == current

                item.background =
                    GradientDrawable().apply {
                        setColor(
                            if (selected) {
                                Color.rgb(25, 85, 125)
                            } else {
                                panel
                            }
                        )

                        cornerRadius = dp(9).toFloat()

                        setStroke(
                            dp(2),
                            if (selected) cyan
                            else Color.DKGRAY
                        )
                    }
            }
        }

        timeoutOptions.forEach { seconds ->
            val choice = button(
                "$seconds seconds"
            ) {
                settings.edit()
                    .putInt(
                        "controller_timeout",
                        seconds
                    )
                    .apply()

                updateTimeoutButtons()
            }

            timeoutButtons.add(choice)
            root.addView(choice)
        }

        updateTimeoutButtons()

        section("WATCH HISTORY")

        root.addView(
            label(
                "Clear recently watched Live TV channels for this account.",
                15f
            )
        )

        root.addView(
            button("CLEAR RECENT CHANNELS") {
                AlertDialog.Builder(this)
                    .setTitle("Clear watch history?")
                    .setMessage(
                        "This removes your recently watched channels. " +
                        "Your favourites will remain saved."
                    )
                    .setNegativeButton(
                        "CANCEL",
                        null
                    )
                    .setPositiveButton(
                        "CLEAR"
                    ) { _, _ ->
                        val serverIndex =
                            intent.getIntExtra(
                                "server_index",
                                -1
                            )

                        val username =
                            intent.getStringExtra(
                                "username"
                            ).orEmpty()

                        if (
                            serverIndex !in
                            XtreamApi.servers.indices ||
                            username.isBlank()
                        ) {
                            Toast.makeText(
                                this,
                                "Account unavailable",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val account =
                                XtreamApi.Account(
                                    XtreamApi.servers[
                                        serverIndex
                                    ],
                                    username,
                                    ""
                                )

                            RecentChannelsStore.clear(
                                this,
                                account
                            )

                            Toast.makeText(
                                this,
                                "Recent channels cleared",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .show()
            }
        )

        section("ACCOUNT")

        val serverIndex = intent.getIntExtra(
            "server_index",
            -1
        )

        val serverName =
            XtreamApi.servers.getOrNull(
                serverIndex
            )?.name ?: "Not connected"

        root.addView(
            label("Current server: $serverName")
        )

        section("ABOUT")

        root.addView(
            label(
                "DreamStream Ultra TV\n" +
                "Android TV / Fire TV\n" +
                "Stage 3 — Settings"
            )
        )
    }
}
