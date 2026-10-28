package com.dreamstream.ultratv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val navy = Color.rgb(9, 14, 30)
    private val cyan = Color.rgb(48, 207, 255)

    private val executor =
        Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences(
            "tv_login",
            MODE_PRIVATE
        )
    }

    private lateinit var root: LinearLayout

    private var autoLoginAttempted = false
    private var account: XtreamApi.Account? = null
    private var currentType = "live"

    private var allStreams =
        emptyList<XtreamApi.Stream>()

    private var categories =
        emptyList<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
    }

    private fun text(
        value: String,
        size: Float = 19f
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(14, 12, 14, 12)
    }

    private fun button(
        value: String,
        click: () -> Unit
    ): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 18f
        isFocusable = true
        setOnClickListener { click() }
    }

    private fun screen(title: String) {

        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 24, 30, 30)
        }

        scroll.addView(root)
        setContentView(scroll)

        root.addView(
            text(title, 29f).apply {
                setTextColor(cyan)
                setTypeface(
                    null,
                    Typeface.BOLD
                )
            }
        )
    }

    private fun runNetwork(task: () -> Unit) {

        executor.execute {
            try {
                task()
            } catch (error: Exception) {

                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message
                            ?: "Connection failed",
                        Toast.LENGTH_LONG
                    ).show()

                    if (account == null) {
                        showLogin()
                    } else {
                        showHome()
                    }
                }
            }
        }
    }

    private fun showLogin() {

        screen("DREAMSTREAM ULTRA TV")

        root.addView(
            text(
                "Sign in to your authorised IPTV provider"
            )
        )

        val remember =
            prefs.getBoolean(
                "remember",
                false
            )

        val rememberPassword =
            prefs.getBoolean(
                "remember_password",
                false
            )

        val savedPassword =
            if (rememberPassword) {
                SecurePasswordStore.read(this)
            } else {
                null
            }

        val server = Spinner(this).apply {

            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                XtreamApi.servers.map {
                    it.name
                }
            )

            setSelection(
                (
                    if (remember) {
                        prefs.getInt(
                            "server",
                            0
                        )
                    } else {
                        0
                    }
                ).coerceIn(
                    0,
                    XtreamApi.servers.lastIndex
                )
            )
        }

        val username = EditText(this).apply {
            hint = "Username"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)

            if (remember) {
                setText(
                    prefs.getString(
                        "username",
                        ""
                    )
                )
            }
        }

        val password = EditText(this).apply {

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

        val rememberUserBox =
            CheckBox(this).apply {
                text =
                    "Remember Username and Server"

                setTextColor(Color.WHITE)
                isChecked = remember
            }

        val rememberPassBox =
            CheckBox(this).apply {
                text =
                    "Remember Password (encrypted on this device)"

                setTextColor(Color.WHITE)

                isChecked =
                    rememberPassword &&
                    savedPassword != null
            }

        val autoBox = CheckBox(this).apply {
            text =
                "Auto Login when DreamStream starts"

            setTextColor(Color.WHITE)

            isChecked =
                prefs.getBoolean(
                    "auto_login",
                    false
                ) &&
                rememberUserBox.isChecked &&
                rememberPassBox.isChecked
        }

        rememberUserBox.setOnCheckedChangeListener {
            _, checked ->

            if (!checked) {
                autoBox.isChecked = false
            }
        }

        rememberPassBox.setOnCheckedChangeListener {
            _, checked ->

            if (!checked) {
                autoBox.isChecked = false
            }
        }

        autoBox.setOnCheckedChangeListener {
            _, checked ->

            if (checked) {
                rememberUserBox.isChecked = true
                rememberPassBox.isChecked = true
            }
        }

        root.addView(server)
        root.addView(username)
        root.addView(password)
        root.addView(rememberUserBox)
        root.addView(rememberPassBox)
        root.addView(autoBox)

        var connecting = false

        fun connect() {

            if (connecting) return

            val user =
                username.text.toString().trim()

            val pass =
                password.text.toString()

            if (
                user.isBlank() ||
                pass.isBlank()
            ) {
                Toast.makeText(
                    this,
                    "Enter username and password",
                    Toast.LENGTH_SHORT
                ).show()

                return
            }

            connecting = true

            val index =
                server.selectedItemPosition

            val selected =
                XtreamApi.Account(
                    XtreamApi.servers[index],
                    user,
                    pass
                )

            root.addView(
                text("Connecting...", 16f)
            )

            executor.execute {
                try {

                    XtreamApi.authenticate(
                        selected
                    )

                    runOnUiThread {

                        val saveUser =
                            rememberUserBox.isChecked

                        val savePass =
                            rememberPassBox.isChecked

                        var passwordStored = false

                        if (savePass) {
                            try {

                                SecurePasswordStore.save(
                                    this,
                                    pass
                                )

                                passwordStored =
                                    SecurePasswordStore.read(
                                        this
                                    ) == pass

                            } catch (_: Exception) {

                                SecurePasswordStore.clear(
                                    this
                                )

                                Toast.makeText(
                                    this,
                                    "Could not securely save password",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            SecurePasswordStore.clear(
                                this
                            )
                        }

                        prefs.edit()
                            .putBoolean(
                                "remember",
                                saveUser
                            )
                            .putInt(
                                "server",
                                if (saveUser) {
                                    index
                                } else {
                                    0
                                }
                            )
                            .putString(
                                "username",
                                if (saveUser) {
                                    user
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
                                autoBox.isChecked &&
                                    saveUser &&
                                    passwordStored
                            )
                            .apply()

                        account = selected
                        showHome()
                    }

                } catch (error: Exception) {

                    runOnUiThread {
                        connecting = false

                        Toast.makeText(
                            this,
                            error.message
                                ?: "Login failed",
                            Toast.LENGTH_LONG
                        ).show()

                        showLogin()
                    }
                }
            }
        }

        root.addView(
            button("CONNECT") {
                connect()
            }
        )

        if (!autoLoginAttempted) {

            autoLoginAttempted = true

            if (
                autoBox.isChecked &&
                username.text.isNotBlank() &&
                password.text.isNotBlank()
            ) {
                connect()
            }
        }
    }

    private fun tile(
        title: String,
        subtitle: String,
        click: () -> Unit
    ): View {

        val card = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true

            setPadding(
                22,
                19,
                22,
                19
            )

            background =
                GradientDrawable().apply {
                    setColor(
                        Color.rgb(
                            22,
                            33,
                            57
                        )
                    )

                    cornerRadius = 18f

                    setStroke(
                        2,
                        Color.rgb(
                            48,
                            75,
                            112
                        )
                    )
                }

            setOnClickListener {
                click()
            }

            setOnFocusChangeListener {
                _, focused ->

                background =
                    GradientDrawable().apply {

                        setColor(
                            if (focused) {
                                Color.rgb(
                                    32,
                                    66,
                                    106
                                )
                            } else {
                                Color.rgb(
                                    22,
                                    33,
                                    57
                                )
                            }
                        )

                        cornerRadius = 18f

                        setStroke(
                            if (focused) {
                                5
                            } else {
                                2
                            },
                            if (focused) {
                                cyan
                            } else {
                                Color.rgb(
                                    48,
                                    75,
                                    112
                                )
                            }
                        )
                    }

                scaleX =
                    if (focused) 1.02f else 1f

                scaleY =
                    if (focused) 1.02f else 1f
            }
        }

        card.addView(
            text(title, 23f).apply {
                setTypeface(
                    null,
                    Typeface.BOLD
                )
            }
        )

        card.addView(
            text(subtitle, 15f).apply {
                setTextColor(
                    Color.LTGRAY
                )
            }
        )

        return card
    }

    private fun showHome() {

        screen("DREAMSTREAM ULTRA TV")

        root.addView(
            text(
                "Welcome - ${account?.server?.name}",
                18f
            )
        )

        root.addView(
            text(
                "YOUR ENTERTAINMENT",
                16f
            ).apply {
                setTextColor(cyan)
            }
        )

        root.addView(
            tile(
                "LIVE TV",
                "Browse your live channels"
            ) {
                load("live")
            }
        )

        root.addView(
            tile(
                "SMART TV GUIDE",
                "UK, USA, Canada & New Zealand"
            ) {
                val selected = account

                if (selected != null) {
                    SmartEpg.show(
                        this,
                        selected
                    )
                }
            }
        )

        root.addView(
            tile(
                "MOVIES",
                "Explore your film library"
            ) {
                load("movie")
            }
        )

        root.addView(
            tile(
                "SERIES",
                "Browse shows and episodes"
            ) {
                load("series")
            }
        )

        root.addView(
            tile(
                "MY FAVOURITES",
                "Saved channels, films and shows"
            ) {
                showFavourites()
            }
        )

        root.addView(
            text(
                "SPORTS",
                16f
            ).apply {
                setTextColor(cyan)
            }
        )

        root.addView(
            tile(
                "SPORTS NEWS",
                "News, articles and saved stories"
            ) {
                startActivity(
                    Intent(
                        this,
                        SportsNewsActivity::class.java
                    )
                )
            }
        )

        root.addView(
            tile(
                "LIVE SPORTS FINDER",
                "Find matches in your own playlists"
            ) {
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

        val selected =
            account ?: return showLogin()

        currentType = type

        screen(
            "LOADING ${type.uppercase()}..."
        )

        root.addView(
            text(
                "Retrieving your provider catalogue...",
                16f
            )
        )

        runNetwork {

            val loadedCategories =
                XtreamApi.categories(
                    selected,
                    type
                )

            val loadedStreams =
                XtreamApi.streams(
                    selected,
                    type
                )

            runOnUiThread {
                categories =
                    loadedCategories

                allStreams =
                    loadedStreams

                showLibrary()
            }
        }
    }

    private fun showLibrary() {

        screen(
            "${currentType.uppercase()} / ${account?.server?.name}"
        )

        root.addView(
            button("HOME") {
                showHome()
            }
        )

        val search = EditText(this).apply {
            hint = "Search titles"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        root.addView(search)

        val options =
            listOf(
                "" to "All categories"
            ) + categories

        val category = Spinner(this).apply {

            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map {
                    it.second
                }
            )
        }

        root.addView(category)

        val results =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        root.addView(results)

        fun refresh() {

            results.removeAllViews()

            val query =
                search.text.toString().trim()

            val selectedCategory =
                options[
                    category.selectedItemPosition
                        .coerceAtLeast(0)
                ].first

            val matches =
                allStreams.filter {

                    (
                        selectedCategory.isEmpty() ||
                        it.categoryId ==
                            selectedCategory
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
                text(
                    "${matches.size} results",
                    16f
                )
            )

            matches.take(200).forEach {
                stream ->

                val row =
                    LinearLayout(this).apply {
                        orientation =
                            LinearLayout.HORIZONTAL
                    }

                val open =
                    button(stream.title) {

                        if (
                            stream.type == "series"
                        ) {
                            showEpisodes(stream)
                        } else {
                            play(stream)
                        }
                    }

                row.addView(
                    open,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                val favourite =
                    button(
                        if (
                            FavouritesStore.contains(
                                this,
                                account!!,
                                stream
                            )
                        ) {
                            "★"
                        } else {
                            "☆"
                        }
                    ) {

                        val selected =
                            account ?: return@button

                        FavouritesStore.toggle(
                            this,
                            selected,
                            stream
                        )

                        refresh()
                    }

                row.addView(favourite)
                results.addView(row)
            }

            if (matches.size > 200) {

                results.addView(
                    text(
                        "Showing first 200. Search to narrow results.",
                        15f
                    )
                )
            }
        }

        category.onItemSelectedListener =
            object :
                AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) = Unit

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    refresh()
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
                    refresh()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) = Unit
            }
        )

        refresh()
    }

    private fun showFavourites() {

        val selected =
            account ?: return showLogin()

        screen("MY FAVOURITES")

        root.addView(
            button("HOME") {
                showHome()
            }
        )

        val entries =
            FavouritesStore.list(
                this,
                selected
            )

        if (entries.isEmpty()) {
            root.addView(
                text(
                    "No favourites yet. Open Live TV, Movies or Series and select ☆ to save an item."
                )
            )
        }

        entries.groupBy {
            it.type
        }.forEach { (type, items) ->

            root.addView(
                text(
                    type.uppercase(),
                    21f
                ).apply {
                    setTextColor(cyan)
                }
            )

            items.forEach { stream ->

                val row =
                    LinearLayout(this).apply {
                        orientation =
                            LinearLayout.HORIZONTAL
                    }

                row.addView(
                    button(stream.title) {

                        if (
                            stream.type == "series"
                        ) {
                            showEpisodes(stream)
                        } else {
                            play(stream)
                        }
                    },
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )

                row.addView(
                    button("REMOVE") {

                        FavouritesStore.toggle(
                            this,
                            selected,
                            stream
                        )

                        showFavourites()
                    }
                )

                root.addView(row)
            }
        }
    }

    private fun showEpisodes(
        series: XtreamApi.Stream
    ) {

        val selected =
            account ?: return

        screen(
            "EPISODES / ${series.title}"
        )

        root.addView(
            button("BACK TO SERIES") {
                load("series")
            }
        )

        root.addView(
            text(
                "Loading episodes...",
                16f
            )
        )

        runNetwork {

            val episodes =
                XtreamApi.seriesEpisodes(
                    selected,
                    series.id
                )

            runOnUiThread {

                screen(
                    "EPISODES / ${series.title}"
                )

                root.addView(
                    button("BACK TO SERIES") {
                        load("series")
                    }
                )

                if (episodes.isEmpty()) {

                    root.addView(
                        text(
                            "No episodes available"
                        )
                    )
                }

                episodes.forEach { episode ->

                    root.addView(
                        button(
                            episode.title
                        ) {
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

        val selected =
            account ?: return

        val url =
            XtreamApi.playbackUrl(
                selected,
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
