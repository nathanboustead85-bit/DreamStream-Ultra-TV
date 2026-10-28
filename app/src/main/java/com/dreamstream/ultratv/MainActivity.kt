package com.dreamstream.ultratv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Integration entry point for the Sports News and Live Sports Finder modules. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(9, 14, 30)
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 34, 42, 34)
        }
        scroll.addView(layout)
        setContentView(scroll)
        layout.addView(TextView(this).apply {
            text = "DREAMSTREAM ULTRA TV"
            textSize = 32f
            setTextColor(Color.rgb(48, 207, 255))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        })
        layout.addView(TextView(this).apply {
            text = "Sports features • Development build"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 18)
        })
        fun addButton(title: String, target: Class<out Activity>) {
            layout.addView(Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 20f
                isFocusable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, target)) }
            })
        }
        addButton("Sports News Hub", SportsNewsActivity::class.java)
        addButton("Favourites / Live Sports Finder", LiveSportsFinderActivity::class.java)
        layout.addView(TextView(this).apply {
            text = "Add your own authorised M3U and XMLTV sources in Live Sports Finder. " +
                "This development entry screen does not replace a complete IPTV client."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 0)
        })
    }
}
