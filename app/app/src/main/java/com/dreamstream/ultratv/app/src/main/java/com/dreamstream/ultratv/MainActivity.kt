package com.dreamstream.ultratv

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(8, 12, 27))
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "DREAMSTREAM ULTRA TV"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "TV foundation ready — choose a section with your remote"
            textSize = 17f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        })
        listOf("Live TV", "Movies", "Series", "Favourites", "Settings").forEach { section ->
            root.addView(TextView(this).apply {
                text = section
                textSize = 21f
                setTextColor(Color.WHITE)
                setPadding(30, 14, 30, 14)
                isFocusable = true
                setOnFocusChangeListener { view, focused ->
                    view.setBackgroundColor(if (focused) Color.rgb(43, 93, 190) else Color.TRANSPARENT)
                }
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "$section coming next", Toast.LENGTH_SHORT).show()
                }
            })
        }
        setContentView(root)
    }
}
