package com.dreamstream.ultratv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    private lateinit var channelBanner: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var streamUrl = ""
    private var channelName = ""
    private var channelIndex = 0

    private var channelUrls = emptyList<String>()
    private var channelNames = emptyList<String>()

    private var bannerGeneration = 0
    private var playbackGeneration = 0

    private val hideBannerRunnable = Runnable {
        channelBanner.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        streamUrl = intent.getStringExtra("url").orEmpty()
        channelName = intent.getStringExtra(
            "channel_name"
        ).orEmpty()

        channelUrls = intent.getStringArrayListExtra(
            "channel_urls"
        )?.toList() ?: emptyList()

        channelNames = intent.getStringArrayListExtra(
            "channel_names"
        )?.toList() ?: emptyList()

        channelIndex = intent.getIntExtra(
            "channel_index", 0
        )

        if (
            channelUrls.isNotEmpty() &&
            channelUrls.size == channelNames.size
        ) {
            channelIndex = channelIndex.coerceIn(
                0,
                channelUrls.lastIndex
            )

            streamUrl = channelUrls[channelIndex]
            channelName = channelNames[channelIndex]
        } else {
            channelUrls = emptyList()
            channelNames = emptyList()
            channelIndex = 0
        }

        if (streamUrl.isBlank()) {
            finish()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
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

        channelBanner = TextView(this).apply {
            textSize = 21f
            setTextColor(Color.WHITE)
            setBackgroundColor(
                Color.rgb(18, 37, 68)
            )
            setPadding(30, 18, 30, 18)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        container.addView(
            channelBanner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        statusText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(
                Color.rgb(24, 33, 54)
            )
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
            visibility = View.GONE
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

        container.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        initialisePlayer()
    }

    private fun initialisePlayer() {
        if (player != null || streamUrl.isBlank()) {
            return
        }

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
                            showStatus(
                                "Playback finished"
                            )
                        }
                    }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {
                    showStatus(
                        "Unable to play this stream. " +
                        "Try another channel or press Back."
                    )
                }
            }
        )

        loadStream(streamUrl)
    }

    private fun loadStream(url: String) {
        val exo = player ?: return

        playbackGeneration++
        streamUrl = url

        showStatus("Loading stream...")

        exo.stop()
        exo.clearMediaItems()

        exo.setMediaItem(
            MediaItem.fromUri(url)
        )

        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showChannelBanner() {
        bannerGeneration++

        val name = channelName.ifBlank {
            "Live TV"
        }

        channelBanner.text =
            if (channelUrls.isNotEmpty()) {
                "${channelIndex + 1} / " +
                "${channelUrls.size}   $name"
            } else {
                name
            }

        channelBanner.visibility = View.VISIBLE

        handler.removeCallbacks(
            hideBannerRunnable
        )

        handler.postDelayed(
            hideBannerRunnable,
            3500L
        )
    }

    private fun switchChannel(direction: Int) {
        if (channelUrls.size < 2) {
            showChannelBanner()
            return
        }

        channelIndex =
            (
                channelIndex +
                direction +
                channelUrls.size
            ) % channelUrls.size

        channelName = channelNames[channelIndex]

        loadStream(
            channelUrls[channelIndex]
        )

        showChannelBanner()

        val result = android.content.Intent().apply {
            putExtra(
                "last_channel_url",
                streamUrl
            )
        }

        setResult(RESULT_OK, result)
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusText.visibility = View.GONE
    }

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.repeatCount == 0) {
                        switchChannel(1)
                    }
                    return true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.repeatCount == 0) {
                        switchChannel(-1)
                    }
                    return true
                }

                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val exo = player

                    if (exo != null) {
                        if (exo.isPlaying) {
                            exo.pause()
                        } else {
                            exo.play()
                        }
                    }

                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    if (
                        playerView.isControllerFullyVisible
                    ) {
                        playerView.hideController()
                    } else {
                        playerView.showController()
                    }

                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val exo = player

                    if (
                        exo?.isCurrentMediaItemSeekable == true
                    ) {
                        exo.seekTo(
                            (exo.currentPosition - 10000L)
                                .coerceAtLeast(0L)
                        )
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val exo = player

                    if (
                        exo?.isCurrentMediaItemSeekable == true
                    ) {
                        val target =
                            exo.currentPosition + 10000L

                        val duration = exo.duration

                        exo.seekTo(
                            if (duration > 0L) {
                                target.coerceAtMost(
                                    duration
                                )
                            } else {
                                target
                            }
                        )

                        return true
                    }
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        handler.removeCallbacks(
            hideBannerRunnable
        )

        playerView.player = null
        player?.release()
        player = null

        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
