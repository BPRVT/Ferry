package com.ferry.receiver.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * VideoFit — decides what size to lay the video Surface out at, given the source and the screen.
 *
 * Lives in `util` rather than `ui` deliberately: it is pure arithmetic with no Android types, so it
 * is unit-testable from both the app module and the standalone `test-runner`, which excludes the
 * whole `ui` package. [com.ferry.receiver.ui.StreamingScreen] is then only responsible for applying
 * the result to a LayoutParams.
 *
 * ── The problem ──
 * A source's aspect ratio is a property of the sending device and cannot be changed. An iPad's
 * screen really is 4:3; a landscape iPhone is about 19.5:9; the TV is 16:9. Nothing the receiver
 * advertises makes a device render a different shape, so the only three outcomes available are:
 * show everything and accept bars, crop to fill, or stretch and distort.
 *
 * ── What this does ──
 * [FIT] shows everything. [smartFill] fills the screen but refuses to crop more than
 * [MAX_CROP_FRACTION], falling back to thinner bars once that budget is spent. So a source close to
 * 16:9 goes fullscreen, and a very differently-shaped source loses a controlled sliver rather than
 * either a quarter of the screen to black bars or a quarter of its own content to a crop.
 */
object VideoFit {

    /**
     * Most of either dimension smart fill will crop away, as a fraction of the displayed image.
     *
     * 15% is chosen so the two devices in use land either side of it: a landscape iPhone needs no
     * crop worth mentioning, while a 4:3 iPad would need 25% to fill completely — so it takes the
     * 15% and keeps ~10% as (much thinner) side bars. On an iPad that slice is the status bar and
     * the dock area rather than anything in the middle of the screen.
     */
    const val MAX_CROP_FRACTION = 0.15f

    /**
     * The size to lay the video surface out at, in pixels.
     *
     * May be *larger* than the container in one dimension — that is what produces a crop, since the
     * parent clips. Returns null when any input is unknown or nonsensical, meaning "fill the
     * container", which is what the caller does before the first frame establishes a real size.
     *
     * @param videoW     decoded source width  (StreamStats.videoWidth)
     * @param videoH     decoded source height (StreamStats.videoHeight)
     * @param containerW the view's width
     * @param containerH the view's height
     * @param smartFill  false = show everything (bars); true = fill, cropping up to the cap
     */
    fun targetSize(
        videoW: Int,
        videoH: Int,
        containerW: Int,
        containerH: Int,
        smartFill: Boolean,
    ): Pair<Int, Int>? {
        if (videoW <= 0 || videoH <= 0 || containerW <= 0 || containerH <= 0) return null

        val scaleX = containerW.toFloat() / videoW
        val scaleY = containerH.toFloat() / videoH

        // The scale that shows the whole image (bars), and the one that covers the screen (crop).
        // When the source is already 16:9 these are equal and every mode agrees.
        val fitScale = min(scaleX, scaleY)
        val fillScale = max(scaleX, scaleY)

        val scale = if (!smartFill) {
            fitScale
        } else {
            // Cropping a fraction f of a dimension means scaling up by 1/(1-f) past the fit scale,
            // so the cap converts directly into a ceiling on the scale. Never exceed fillScale —
            // going past it would crop with nothing left to gain.
            min(fillScale, fitScale / (1f - MAX_CROP_FRACTION))
        }

        return Pair((videoW * scale).roundToInt(), (videoH * scale).roundToInt())
    }
}
