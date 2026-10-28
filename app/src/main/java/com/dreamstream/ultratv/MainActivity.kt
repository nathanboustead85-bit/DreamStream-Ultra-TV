package com.dreamstream.ultratv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val background = Color.rgb(9, 14, 30)
    private val accent = Color.rgb(48, 207, 255)

    private val executor = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("tv_login", MODE_PRIVATE)
    }

    private lateinit var root: LinearLayout

    private var autoLoginAttempted = false
    private var account: XtreamApi.Account? = null
    private var categoryFilter = ""
    private var currentType = "live"

    private var allStreams =
        emptyList<XtreamApi.Stream>()

    private var categories =
        emptyList<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
    }

    private fun label(
        value: String,
        size: Float = 20f
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(14, 12, 14, 12)
    }

    private fun button(
        value: String,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = value
        isAllCaps = false
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun screen(title: String) {

        val scroll = ScrollView(this).apply {
            setBackgroundColor(
                this@MainActivity.background
            )
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        scroll.addView(root)
        setContentView(scroll)

        root.addView(
            label(title, 30f).apply {
                setTextColor(
                    this@MainActivity.accent
                )
            }
        )
    }

    private fun work(
        message: String,
        task: () -> Unit
    ) {
        root.addView(label(message, 16f))

        executor.execute {
            try {
                task()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        e.message ?: "Network error",
                        Toast.LENGTH_LONG
                    ).show()

                    showHomeOrLogin()
                }
            }
        }
    }

    private fun showHomeOrLogin() {
        if (account == null) {
            showLogin()
        } else {
            showHome()
        }
    }

    private fun showLogin() {

        screen("DREAMSTREAM ULTRA TV")

        root.addView(
            label(
                "Sign in to your authorised IPTV provider"
            )
        )

        val rememberUsername =
            prefs.getBoolean("remember", false)

        val rememberPassword =
            prefs.getBoolean(
                "remember_password",
                false
            )

        val autoLogin =
            prefs.getBoolean("auto_login", false)

        val savedPassword =
            if (rememberPassword) {
                SecurePasswordStore.read(this)
            } else {
                null
            }

        val spinner = Spinner(this)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            XtreamApi.servers.map { it.name }
        )

        spinner.setSelection(
            (
                if (rememberUsername) {
                    prefs.getInt("server", 0)
                } else {
                    0
                }
            ).coerceIn(
                0,
                XtreamApi.servers.lastIndex
            )
        )

        root.addView(spinner)

        val user = EditText(this).apply {
            hint = "Username"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)

            if (rememberUsername) {
                setText(
                    prefs.getString("username", "")
                )
            }
        }

        val pass = EditText(this).apply {
            hint = "Password"
            setSingleLine(true)

            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)

            if (savedPassword != null) {
                setText(savedPassword)
            }
        }

        val rememberUserBox = CheckBox(this).apply {
            text = "Remember Username and Server"
            setTextColor(Color.WHITE)
            isChecked = rememberUsername
        }

        val rememberPassBox = CheckBox(this).apply {
            text =
                "Remember Password (encrypted on this device)"

            setTextColor(Color.WHITE)

            isChecked =
                rememberPassword &&
                savedPassword != null
        }

        val autoLoginBox = CheckBox(this).apply {
            text =
                "Auto Login when DreamStream starts"

            setTextColor(Color.WHITE)

            isChecked =
                autoLogin &&
                rememberUserBox.isChecked &&
                rememberPassBox.isChecked
        }

        rememberUserBox.setOnCheckedChangeListener {
            _, checked ->

            if (!checked) {
                autoLoginBox.isChecked = false
            }
        }

        rememberPassBox.setOnCheckedChangeListener {
            _, checked ->

            if (!checked) {
                autoLoginBox.isChecked = false
            }
        }

        autoLoginBox.setOnCheckedChangeListener {
            _, checked ->

            if (checked) {
                rememberUserBox.isChecked = true
                rememberPassBox.isChecked = true
            }
        }

        root.addView(user)
        root.addView(pass)
        root.addView(rememberUserBox)
        root.addView(rememberPassBox)
        root.addView(autoLoginBox)

        fun connect() {

            val username =
                user.text.toString().trim()

            val password =
                pass.text.toString()

            if (
                username.isBlank() ||
                password.isBlank()
            ) {
                Toast.makeText(
                    this,
                    "Enter username and password",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

            val serverIndex =
                spinner.selectedItemPosition

            val selectedAccount =
                XtreamApi.Account(
                    XtreamApi.servers[serverIndex],
                    username,
                    password
                )

            work("Connecting...") {

                XtreamApi.authenticate(
                    selectedAccount
                )

                runOnUiThread {

                    val saveUser =
                        rememberUserBox.isChecked

                    val savePass =
                        rememberPassBox.isChecked

                    val saveAuto =
                        autoLoginBox.isChecked &&
                        saveUser &&
                        savePass

                    if (savePass) {

                        try {
                            SecurePasswordStore.save(
                                this,
                                password
                            )
                        } catch (e: Exception) {

                            Toast.makeText(
                                this,
                                "Password could not be saved securely",
                                Toast.LENGTH_LONG
                            ).show()

                            SecurePasswordStore.clear(this)
                        }

                    } else {
                        SecurePasswordStore.clear(this)
                    }

                    val passwordStored =
                        savePass &&
                        SecurePasswordStore.read(this) != null

                    prefs.edit()
                        .putBoolean(
                            "remember",
                            saveUser
                        )
                        .putInt(
                            "server",
                            if (saveUser) {
                                serverIndex
                            } else {
                                0
                            }
                        )
                        .putString(
                            "username",
                            if (saveUser) {
                                username
                            } else {
                                ""
                            }
                        )
                        .putBoolean(
                            "remember_password",
                            passwordStored
                        )
                        .putBoolean(
                            "auto_login",
                            saveAuto && passwordStored
                        )
                        .apply()

                    account = selectedAccount

                    showHome()
                }
            }
        }

        root.addView(
            button("Connect") {
                connect()
            }
        )

        if (!autoLoginAttempted) {

            autoLoginAttempted = true

            if (
                autoLoginBox.isChecked &&
                user.text.toString().isNotBlank() &&
                pass.text.toString().isNotBlank()
            ) {
                connect()
            }
        }
    }

    private fun showHome() {

        screen("DREAMSTREAM ULTRA TV")

        root.addView(
            label(
                "Connected: ${account?.server?.name}"
            )
        )

        root.addView(
            button("LIVE TV") {
                load("live")
            }
        )

        root.addView(
            button("MOVIES") {
                load("movie")
            }
        )

        root.addView(
            button("SERIES") {
                load("series")
            }
        )

        root.addView(
            button("SPORTS NEWS") {
                startActivity(
                    Intent(
                        this,
                        SportsNewsActivity::class.java
                    )
                )
            }
        )

        root.addView(
            button("LIVE SPORTS FINDER") {
                startActivity(
                    Intent(
                        this,
                        LiveSportsFinderActivity::class.java
                    )
                )
            }
        )

        root.addView(
            button("Log out") {
                account = null

                prefs.edit()
                    .putBoolean(
                        "auto_login",
                        false
                    )
                    .apply()

                showLogin()
            }
        )
    }

    private fun load(type: String) {

        val selectedAccount =
            account ?: run {
                showLogin()
                return
            }

        currentType = type

        screen(
            "Loading ${type.uppercase()}..."
        )

        work("Fetching categories and channels") {

            val loadedCategories =
                XtreamApi.categories(
                    selectedAccount,
                    type
                )

            val loadedStreams =
                XtreamApi.streams(
                    selectedAccount,
                    type
                )

            runOnUiThread {

                categories = loadedCategories
                allStreams = loadedStreams
                categoryFilter = ""

                showLibrary()
            }
        }
    }

    private fun showLibrary() {

        screen(
            "${currentType.uppercase()} / ${account?.server?.name}"
        )

        root.addView(
            button("Home") {
                showHome()
            }
        )

        val filter = EditText(this).apply {
            hint = "Search titles"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        root.addView(filter)

        val categorySpinner = Spinner(this)

        val options =
            listOf("" to "All categories") +
            categories

        categorySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.second }
        )

        root.addView(categorySpinner)

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(results)

        fun refresh() {

            results.removeAllViews()

            val query =
                filter.text.toString().trim()

            val matching =
                allStreams.filter {

                    (
                        categoryFilter.isEmpty() ||
                        it.categoryId == categoryFilter
                    ) &&
                    (
                        query.isEmpty() ||
                        it.title.contains(
                            query,
                            ignoreCase = true
                        )
                    )
                }

            results.addView(
                label(
                    "${matching.size} results",
                    16f
                )
            )

            matching.take(250).forEach { stream ->

                results.addView(
                    button(stream.title) {

                        if (stream.type == "series") {
                            showEpisodes(stream)
                        } else {
                            play(stream)
                        }
                    }
                )
            }

            if (matching.size > 250) {

                results.addView(
                    label(
                        "Showing first 250 results. Use search to narrow down.",
                        16f
                    )
                )
            }
        }

        categorySpinner.onItemSelectedListener =
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

                    categoryFilter =
                        options[position].first

                    refresh()
                }
            }

        filter.addTextChangedListener(
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
                    refresh()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        refresh()
    }

    private fun showEpisodes(
        series: XtreamApi.Stream
    ) {

        val selectedAccount =
            account ?: return

        screen(
            "EPISODES / ${series.title}"
        )

        root.addView(
            button("Back to series") {
                showLibrary()
            }
        )

        work("Loading episodes...") {

            val episodes =
                XtreamApi.seriesEpisodes(
                    selectedAccount,
                    series.id
                )

            runOnUiThread {

                if (episodes.isEmpty()) {

                    root.addView(
                        label(
                            "No episodes available"
                        )
                    )
                }

                episodes.forEach { episode ->

                    root.addView(
                        button(episode.title) {
                            play(episode)
                        }
                    )
                }
            }
        }
    }

    private fun play(
        stream: XtreamApi.Stream
    ) {

        val selectedAccount =
            account ?: return

        val url =
            XtreamApi.playbackUrl(
                selectedAccount,
                stream
            )

        startActivity(
            Intent(
                this,
                PlayerActivity::class.java
            ).putExtra(
                "url",
                url
            )
        )
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
