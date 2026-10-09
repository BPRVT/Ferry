package com.ferry.receiver.airplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.ferry.receiver.util.Logger

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). The TV fetches
 * the media itself and renders it to the same streaming [Surface] the mirror decoder uses; the
 * sender only drives transport (`/rate`, `/scrub`, `/stop`) and polls `/playback-info`.
 *
 * ── Why ExoPlayer (8.1.0) ──
 *
 * On weak Wi-Fi this mode is the best thing that can happen to a video. Mirroring sends every frame
 * live from the iPad, across the Wi-Fi twice; here the iPad drops out entirely and the TV streams
 * from the internet with tens of seconds in hand, so a Wi-Fi drop of a few seconds is simply never
 * seen. That only holds if the player actually buffers ahead, handles HLS (what most AirPlay video
 * is), and recovers from network errors — which Android's built-in `MediaPlayer`, used through
 * 8.0.0, does poorly or not at all. ExoPlayer does all three, and [BUFFER_MIN_MS] /
 * [BUFFER_MAX_MS] tell it to keep a long cushion.
 *
 * ── Threading ──
 *
 * ExoPlayer must only be touched on the thread whose Looper it was built with. RTSP verbs arrive on
 * the RTSP thread, so every call is posted to this player's own [thread]; `/playback-info` reads a
 * [snapshot] that the player thread refreshes, so the RTSP thread never blocks on the player.
 */
class AirPlayVideoPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    private val thread = HandlerThread("FerryUrlPlayer").apply { start() }
    private val handler = Handler(thread.looper)

    /** Player thread only. */
    private var player: ExoPlayer? = null
    private var pendingStartFraction = 0.0
    private var attachedSurface: Surface? = null

    @Volatile private var snapshot: PlaybackInfo? = null
    @Volatile private var released = false

    private val refresh = object : Runnable {
        override fun run() {
            updateSnapshot()
            // Pick up the Surface once the streaming screen has created it (it usually does not
            // exist yet when /play arrives), or a new one after Ferry returns from the background.
            attachSurfaceIfChanged()
            if (player != null) handler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once ready. */
    fun play(url: String, startPositionFraction: Double) {
        Logger.i("AirPlay video: play url=$url start=$startPositionFraction")
        post { start(url, startPositionFraction.coerceIn(0.0, 1.0)) }
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    fun setRate(rate: Float) = post { player?.playWhenReady = rate > 0f }

    /** Seeks to [positionSec] seconds. */
    fun scrub(positionSec: Double) = post { player?.seekTo((positionSec * 1000).toLong().coerceAtLeast(0)) }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    fun info(): PlaybackInfo? = snapshot

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    fun attachSurface() = post { attachSurfaceIfChanged() }

    fun release() {
        if (released) return
        released = true
        snapshot = null
        handler.post {
            releasePlayer()
            thread.quitSafely()
        }
    }

    private fun post(block: () -> Unit) {
        if (released) return
        handler.post { if (!released) runCatching(block).onFailure { Logger.e("AirPlay video: player call failed", it) } }
    }

    @OptIn(markerClass = [UnstableApi::class])
    private fun start(url: String, startFraction: Double) {
        releasePlayer()
        pendingStartFraction = startFraction
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, START_BUFFER_MS, REBUFFER_MS)
            .build()
        val p = ExoPlayer.Builder(context)
            .setLooper(thread.looper)
            .setLoadControl(loadControl)
            .build()
        player = p
        snapshot = PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false,
        )
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== p) return
                when (state) {
                    Player.STATE_READY -> onReady(p)
                    Player.STATE_BUFFERING -> Logger.i("AirPlay video: buffering")
                    Player.STATE_ENDED -> { Logger.i("AirPlay video: completed"); onEnded() }
                    else -> Unit
                }
                updateSnapshot()
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.e("AirPlay video error ${error.errorCodeName}", error)
            }
        })
        attachedSurface = null
        attachSurfaceIfChanged()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        handler.post(refresh)
    }

    /** First READY: apply the start position the sender asked for, now that the duration is known. */
    private fun onReady(p: ExoPlayer) {
        val fraction = pendingStartFraction
        if (fraction > 0.0) {
            pendingStartFraction = 0.0
            val duration = p.duration
            if (duration != C.TIME_UNSET && duration > 0) p.seekTo((fraction * duration).toLong())
        }
        Logger.i("AirPlay video: ready dur=${p.duration}ms")
    }

    private fun attachSurfaceIfChanged() {
        val p = player ?: return
        val surface = surfaceProvider() ?: return
        if (surface === attachedSurface) return
        attachedSurface = surface
        p.setVideoSurface(surface)
    }

    private fun updateSnapshot() {
        val p = player ?: run { snapshot = null; return }
        val ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        snapshot = PlaybackInfo(
            durationSec = duration / 1000.0,
            positionSec = p.currentPosition.coerceAtLeast(0) / 1000.0,
            rate = if (p.isPlaying) 1.0 else 0.0,
            readyToPlay = ready && duration > 0,
        )
    }

    private fun releasePlayer() {
        handler.removeCallbacks(refresh)
        player?.let { runCatching { it.release() } }
        player = null
        attachedSurface = null
    }

    private companion object {
        /** Always keep at least this much buffered ahead — rides out long Wi-Fi drops. */
        const val BUFFER_MIN_MS = 30_000
        /** Buffer up to this far ahead when the connection allows. */
        const val BUFFER_MAX_MS = 60_000
        /** Start playing once this much is in hand: quick start, but not on a knife edge. */
        const val START_BUFFER_MS = 2_500
        /** After running dry, refill this much before resuming, so it does not stutter straight back. */
        const val REBUFFER_MS = 5_000
        const val SNAPSHOT_INTERVAL_MS = 250L
    }
}
