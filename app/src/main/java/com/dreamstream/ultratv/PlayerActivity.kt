package com.dreamstream.ultratv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView

    private var streamUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        streamUrl = intent.getStringExtra("url").orEmpty()

        if (streamUrl.isBlank()) {
            finish()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerAutoShow = true
            setShowBuffering(
                PlayerView.SHOW_BUFFERING_ALWAYS
            )
        }

        container.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setBackgroundColor(Color.rgb(24, 33, 54))
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
        }

        container.addView(
            statusText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(container)
    }

    override fun onStart() {
        super.onStart()
        initialisePlayer()
    }

    private fun initialisePlayer() {
        if (player != null || streamUrl.isBlank()) return

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo

        exo.addListener(
            object : Player.Listener {

                override fun onPlaybackStateChanged(
                    playbackState: Int
                ) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            showStatus("Loading stream...")
                        }

                        Player.STATE_READY -> {
                            hideStatus()
                        }

                        Player.STATE_ENDED -> {
                            showStatus("Playback finished")
                        }
                    }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    showStatus(
                        "Unable to play this stream. " +
                        "Press Back to return."
                    )
                }
            }
        )

        exo.setMediaItem(
            MediaItem.fromUri(streamUrl)
        )

        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = android.view.View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = android.view.View.GONE
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        val exo = player

        when (keyCode) {

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (exo != null) {
                    if (exo.isPlaying) exo.pause()
                    else exo.play()
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exo?.play()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exo?.pause()
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (exo?.isCurrentMediaItemSeekable == true) {
                    exo.seekTo(
                        (exo.currentPosition - 10000L)
                            .coerceAtLeast(0L)
                    )
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (exo?.isCurrentMediaItemSeekable == true) {
                    val target =
                        exo.currentPosition + 10000L

                    val duration = exo.duration

                    exo.seekTo(
                        if (duration > 0L) {
                            target.coerceAtMost(duration)
                        } else {
                            target
                        }
                    )
                    return true
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
