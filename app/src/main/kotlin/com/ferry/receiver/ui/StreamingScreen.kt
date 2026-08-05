package com.ferry.receiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import com.ferry.receiver.airplay.StreamStats
import com.ferry.receiver.util.Logger
import com.ferry.receiver.util.VideoFit

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY: The decoded video from MediaCodec must be rendered to a [Surface].
 * A [SurfaceView] provides a dedicated, hardware-accelerated drawing surface
 * that can receive MediaCodec output directly — no intermediate bitmap copies.
 * This is the lowest-latency way to display video on Android.
 *
 * HOW: Add this view to the streaming_container in activity_main.xml.
 * Call [getSurface] to get the Surface to pass to [VideoDecoder.initialize].
 * The Surface is valid as long as this view is attached to the window.
 *
 * IMPORTANT: The Surface becomes available asynchronously after the view is
 * laid out. [getSurface] returns null if called before the Surface is ready.
 * [VideoDecoder.initialize] must not be called until [getSurface] returns non-null.
 *
 * Example:
 *   val streamingScreen = StreamingScreen(context)
 *   container.addView(streamingScreen)
 *   // Later, when surface is ready:
 *   val surface = streamingScreen.getSurface()
 *   videoDecoder.initialize(spsBytes, ppsBytes, width, height)
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // The SurfaceView that provides the hardware-accelerated rendering surface
    private val surfaceView: SurfaceView = SurfaceView(context)

    // The Surface is created asynchronously by SurfaceView — stored here when ready
    private var surface: Surface? = null

    // Optional debug HUD (Settings → "Debug overlay"), drawn on top of the video.
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    // Last applied surface size, so we only re-layout on an actual change (rotation/resolution switch).
    private var lastSurfaceW = Int.MIN_VALUE
    private var lastSurfaceH = Int.MIN_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else if (debugView.visibility != GONE) {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        // Black backing so the letterbox/pillarbox bars (when the video is sized to its aspect ratio
        // and doesn't fill 16:9) are black — without this, those margins are transparent and the home
        // menu shows through behind the streaming overlay. The SurfaceView punches its own hole on top.
        setBackgroundColor(Color.BLACK)

        // SurfaceView is centred; its size is set to the video's aspect ratio by applyAspectFit().
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // Debug HUD overlay, top-left, above the video surface.
        //
        // Inset to the Android TV overscan-safe area rather than a fixed pixel margin. A TV may
        // crop up to 5% of each edge, and the old margin was 48 *pixels* — about 2.5% on a 1080p
        // panel, so the HUD sat inside the region a set is free to cut off, and on a TV that
        // overscans it simply was not on screen. The documented safe margins are 48dp horizontal
        // and 27dp vertical, which is what these resolve to.
        //
        // Note this is unrelated to smart fill: applyAspectFit only ever resizes surfaceView, so
        // the crop never touched this view.
        val safeX = (SAFE_AREA_DP_X * resources.displayMetrics.density).toInt()
        val safeY = (SAFE_AREA_DP_Y * resources.displayMetrics.density).toInt()
        addView(debugView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = safeY; leftMargin = safeX })

        // Register a callback to track when the Surface is created/destroyed
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Surface is now ready — store reference for VideoDecoder
                surface = holder.surface
                publishRefreshRate()
                Logger.d("StreamingScreen: Surface created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Called when the surface size changes (e.g., resolution change)
                // MediaCodec handles this automatically — no action needed here
                Logger.d("StreamingScreen: Surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface is gone (e.g., screen turned off) — VideoDecoder must stop
                surface = null
                Logger.d("StreamingScreen: Surface destroyed")
            }
        })
    }

    /**
     * Publishes the panel's refresh rate for [com.ferry.receiver.airplay.VideoDecoder] to pace
     * rendering against.
     *
     * Read here because this is the only place in the video path holding a [android.view.Display] to
     * ask — the decoder has a Surface and nothing else. Read at surfaceCreated rather than once at
     * construction, so a TV that renegotiates its mode (an HDMI mode change, or a panel switching
     * rate for 24 fps content) is picked up on the next Surface instead of staying wrong for the
     * rest of the process.
     *
     * A bad reading is not dangerous: [com.ferry.receiver.airplay.VideoDecoder] clamps it.
     */
    private fun publishRefreshRate() {
        val hz = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.refreshRate
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                ?.defaultDisplay?.refreshRate
        }
        if (hz != null && hz > 0f) {
            StreamStats.displayRefreshHz = hz
            Logger.i("StreamingScreen: panel refresh rate ${hz}Hz")
        } else {
            Logger.w("StreamingScreen: could not read panel refresh rate — " +
                     "pacing against ${StreamStats.displayRefreshHz}Hz")
        }
    }

    /**
     * Returns the [Surface] where decoded video frames will be rendered.
     *
     * Returns null if the Surface has not been created yet (the view hasn't
     * been laid out) or if it has been destroyed. The caller must check for
     * null before passing this to [VideoDecoder.initialize].
     *
     * @return The rendering Surface, or null if not yet available.
     */
    fun getSurface(): Surface? = surface

    /**
     * Sizes the SurfaceView for the decoded video instead of stretching it to fill 16:9. Without
     * this, a portrait phone stream is squashed horizontally.
     *
     * The arithmetic lives in [VideoFit] so it can be unit-tested without an Android runtime; see
     * there for what "smart fill" means. Note the result may be *larger* than this container in one
     * dimension when smart fill is on — that is the crop, and it works because this FrameLayout
     * clips its children.
     *
     * Falls back to filling the container when the source size isn't known yet.
     */
    private fun applyAspectFit() {
        val target = VideoFit.targetSize(
            videoW = StreamStats.videoWidth,
            videoH = StreamStats.videoHeight,
            containerW = width,
            containerH = height,
            smartFill = StreamStats.smartFillEnabled,
        )
        val (targetW, targetH) = target
            ?: (LayoutParams.MATCH_PARENT to LayoutParams.MATCH_PARENT)
        if (targetW == lastSurfaceW && targetH == lastSurfaceH) return
        lastSurfaceW = targetW
        lastSurfaceH = targetH
        surfaceView.layoutParams = (surfaceView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)                 // drive aspect-fit + debug HUD
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REFRESH_MS = 200L

        /**
         * Android TV overscan-safe margins in dp — the 5% of each edge a television is free to
         * crop. Anything inside these is not guaranteed to be visible on a real set.
         */
        private const val SAFE_AREA_DP_X = 48f
        private const val SAFE_AREA_DP_Y = 27f
    }
}
