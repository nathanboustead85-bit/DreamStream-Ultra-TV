package com.dreamstream.ultratv

import android.app.Activity
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: run {
            finish()
            return
        }

        val view = PlayerView(this)
        setContentView(view)

        player = ExoPlayer.Builder(this)
            .build()
            .also { exo ->
                view.player = exo

                exo.setMediaItem(
                    MediaItem.fromUri(url)
                )

                exo.prepare()
                exo.playWhenReady = true
            }
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }
}
