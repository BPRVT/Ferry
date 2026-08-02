package com.ferry.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for [VideoFit], using the geometry of the devices this actually ships against:
 * a 1920x1080 TV, a 4:3 iPad, and a 19.5:9 landscape iPhone.
 */
class VideoFitTest {

    private val tvW = 1920
    private val tvH = 1080

    private fun fit(vw: Int, vh: Int, smart: Boolean) = VideoFit.targetSize(vw, vh, tvW, tvH, smart)!!

    /** Aspect ratio must survive every mode — cropping changes what you see, never the shape. */
    private fun assertAspectPreserved(vw: Int, vh: Int, result: Pair<Int, Int>) {
        val source = vw.toFloat() / vh
        val out = result.first.toFloat() / result.second
        assertTrue(
            "aspect ratio must be preserved: source=$source out=$out",
            abs(source - out) < 0.01f
        )
    }

    // ─── Fit mode (smart fill off) ────────────────────────────────────────────

    @Test
    fun `fit mode pillarboxes a 4-3 iPad`() {
        val (w, h) = fit(1440, 1080, smart = false)
        assertEquals("fills the height", tvH, h)
        assertEquals("leaves side bars", 1440, w)
        assertTrue("must not exceed the screen", w <= tvW && h <= tvH)
    }

    @Test
    fun `fit mode letterboxes a wide iPhone`() {
        val (w, h) = fit(2340, 1080, smart = false)
        assertEquals("fills the width", tvW, w)
        assertTrue("leaves bars top and bottom", h < tvH)
        assertTrue("must not exceed the screen", w <= tvW && h <= tvH)
    }

    // ─── Smart fill ───────────────────────────────────────────────────────────

    /**
     * A 4:3 source needs 25% of crop to fill 16:9 — more than the cap allows. So it should take the
     * full 15%, get much closer to filling, and keep the rest as (thinner) side bars.
     */
    @Test
    fun `smart fill crops an iPad by the cap and no more`() {
        val (w, h) = fit(1440, 1080, smart = true)
        assertAspectPreserved(1440, 1080, w to h)

        val croppedFraction = (h - tvH).toFloat() / h
        assertTrue("crop must not exceed the cap, was $croppedFraction",
            croppedFraction <= VideoFit.MAX_CROP_FRACTION + 0.001f)
        assertTrue("should actually use most of the crop budget, was $croppedFraction",
            croppedFraction > VideoFit.MAX_CROP_FRACTION - 0.01f)

        // Bars remain, but far thinner than the 480px of fit mode.
        val barsTotal = tvW - w
        assertTrue("bars should shrink substantially, were $barsTotal", barsTotal in 1..300)
    }

    @Test
    fun `smart fill never crops more than the cap for a wide iPhone either`() {
        val (w, h) = fit(2340, 1080, smart = true)
        assertAspectPreserved(2340, 1080, w to h)
        val croppedFraction = (w - tvW).toFloat() / w
        assertTrue("crop must not exceed the cap, was $croppedFraction",
            croppedFraction <= VideoFit.MAX_CROP_FRACTION + 0.001f)
    }

    /** A source already 16:9 must be untouched by either mode — no crop, no bars, no scaling games. */
    @Test
    fun `a 16-9 source fills exactly in both modes`() {
        for (smart in listOf(false, true)) {
            val (w, h) = fit(1920, 1080, smart)
            assertEquals("width, smart=$smart", tvW, w)
            assertEquals("height, smart=$smart", tvH, h)
        }
        // And a 16:9 source at a different resolution scales cleanly to the same result.
        assertEquals(tvW to tvH, fit(1280, 720, smart = true))
    }

    /**
     * Portrait is the extreme case — a phone held upright needs an enormous crop to fill a 16:9
     * screen, and cropping that hard would be unusable. The cap is what protects it.
     */
    @Test
    fun `a portrait source is protected by the cap`() {
        val (w, h) = fit(1080, 1920, smart = true)
        assertAspectPreserved(1080, 1920, w to h)
        val croppedFraction = (h - tvH).toFloat() / h
        assertTrue("portrait crop must respect the cap, was $croppedFraction",
            croppedFraction <= VideoFit.MAX_CROP_FRACTION + 0.001f)
        assertTrue("portrait must still be pillarboxed, not blown up", w < tvW)
    }

    // ─── Degenerate input ─────────────────────────────────────────────────────

    @Test
    fun `unknown sizes return null so the caller can fill the container`() {
        assertNull(VideoFit.targetSize(0, 0, tvW, tvH, true))
        assertNull(VideoFit.targetSize(1920, 1080, 0, 0, true))
        assertNull(VideoFit.targetSize(-1, 1080, tvW, tvH, false))
    }
}
