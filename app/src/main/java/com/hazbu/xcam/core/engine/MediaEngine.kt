package com.hazbu.xcam.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer

/**
 * Handles Media3/ExoPlayer lifecycle and state management for video injection.
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    
    @Volatile
    private var isBusy = false

    @Volatile
    private var isPlayingInternal = false

    @Volatile
    private var currentPositionInternal = 0L

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = isPlayingInternal

    val currentPosition: Long
        get() = if (Looper.myLooper() == Looper.getMainLooper()) {
            player?.currentPosition ?: 0L
        } else {
            currentPositionInternal
        }

    private fun log(tag: String, msg: String) {
        logAction("[$tag] $msg")
    }

    fun stop() {
        isBusy = true
        mainHandler.post {
            try {
                player?.apply {
                    stop()
                    clearVideoSurface()
                    release()
                }
            } catch (e: Throwable) {
                log("MEDIA-ENGINE", "Stop failed: ${e.message}")
            } finally {
                player = null
                isPlayingInternal = false
                currentPositionInternal = 0L
                videoWidth = 0
                videoHeight = 0
                isBusy = false
            }
        }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        onPrepared: ((ExoPlayer?) -> Unit)? = null
    ) {
        mainHandler.post {
            if (isBusy) return@post
            
            isBusy = true
            try {
                player?.apply {
                    stop()
                    clearVideoSurface()
                    release()
                }
            } catch (_: Throwable) {}

            try {
                val uri = path.toUri()
                log(tag, "Loading media: $path (Video only pipeline)")

                val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
                    .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

                val exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory).build()
                player = exoPlayer

                val mediaItem = MediaItem.fromUri(uri)

                val effects = mutableListOf<Effect>()
                if (isMirrored || rotationAngle != 0) {
                    val scaleX = if (isMirrored) -1f else 1f
                    effects.add(
                        ScaleAndRotateTransformation.Builder()
                            .setScale(scaleX, 1f)
                            .setRotationDegrees(rotationAngle.toFloat())
                            .build()
                    )
                }
                if (effects.isNotEmpty()) {
                    exoPlayer.setVideoEffects(effects)
                }
                
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.setVideoSurface(surface)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE

                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPlayingInternal = isPlaying
                        if (isPlaying) startPositionPolling() else stopPositionPolling()
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0) {
                            videoWidth = videoSize.width
                            videoHeight = videoSize.height
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            if (!isBusy) return
                            isBusy = false
                            
                            if (videoWidth == 0) {
                                videoWidth = exoPlayer.videoSize.width
                                videoHeight = exoPlayer.videoSize.height
                            }

                            try {
                                exoPlayer.play()
                                log(tag, "Player ACTIVE (${videoWidth}x${videoHeight})")
                                onPrepared?.invoke(exoPlayer)
                            } catch (e: Throwable) {
                                log(tag, "Start failed: ${e.message}")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isBusy = false
                        log(tag, "Player Error: ${error.errorCodeName} | ${error.message}")
                        player?.release()
                        player = null
                        isPlayingInternal = false
                    }
                })

                exoPlayer.prepare()

            } catch (e: Throwable) {
                isBusy = false
                log(tag, "Prepare failed: ${e.message}")
                try { player?.release() } catch (_: Throwable) {}
                player = null
                isPlayingInternal = false
            }
        }
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            player?.let {
                currentPositionInternal = it.currentPosition
                if (isPlayingInternal) {
                    mainHandler.postDelayed(this, 50)
                }
            }
        }
    }

    private fun startPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
        mainHandler.post(positionPoller)
    }

    private fun stopPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
    }
}
