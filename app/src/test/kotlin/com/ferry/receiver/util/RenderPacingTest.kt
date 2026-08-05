package com.ferry.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RenderPacer] — the rule deciding whether a decoded frame is shown or handed back
 * unshown.
 *
 * WHY THIS MATTERS: showing every decoded frame is what caused the corruption reported against
 * 5.5.0. A SurfaceView's BufferQueue holds ~3 frames and does not return one until the display has
 * consumed it, so showing frames faster than the panel refreshes saturates it; the codec then stops
 * returning *input* buffers and `decodeNalUnit` destroys frames — breaking the reference chain and
 * corrupting the picture until the sender's next IDR.
 *
 * The rule replaces that with a much cheaper loss: a decoded-but-unshown frame is invisible, because
 * the frame is still decoded and still available as a reference.
 *
 * The asymmetry runs the opposite way to most of this codebase, and the tests are shaped by it:
 *  - skipping too eagerly costs smoothness (visible judder, no corruption),
 *  - skipping too rarely costs the bug back.
 * The load-bearing cases are therefore the *steady-rate* ones — a sender at or below the panel's
 * rate must never have a frame skipped, because at that rate the display can show them all.
 *
 * These drive [RenderPacer.isDueToRender] itself. The rule lives in `util`, apart from
 * `VideoDecoder`, precisely so this can happen: the rest of the decoder is MediaCodec and a
 * nanoTime() read, and the standalone `test-runner` shadows `VideoDecoder` with a stub entirely.
 * Re-implementing the arithmetic in the test would only ever verify the copy.
 */
class RenderPacingTest {

    /**
     * Replays frame arrivals through the real decision function and returns how many were shown.
     *
     * @param arrivalsNs gaps between consecutive decoded frames.
     */
    private fun rendered(arrivalsNs: List<Long>, hz: Float): Int {
        var lastRenderNs = 0L
        var now = 0L
        var shown = 0
        for (gap in arrivalsNs) {
            now += gap
            if (RenderPacer.isDueToRender(now, lastRenderNs, hz)) {
                lastRenderNs = now
                shown++
            }
        }
        return shown
    }

    private fun steady(fps: Double, count: Int): List<Long> =
        List(count) { (1_000_000_000.0 / fps).toLong() }

    /**
     * The case this must not break. 24 fps film on a 60 Hz panel has frames 41 ms apart against a
     * 16.7 ms refresh — the display can show every one, so every one must be shown.
     */
    @Test
    fun `24fps on a 60Hz panel shows every frame`() {
        assertEquals(120, rendered(steady(24.0, 120), 60f))
    }

    /**
     * The case that was breaking on hardware, and the sharpest test here. An iPad mirror rising to
     * ~59 fps is still *below* 60 Hz, so the panel can show all of it — pacing must stay out of the
     * way. If this ever fails, the tolerance is too tight and the fix has started causing judder.
     */
    @Test
    fun `59fps on a 60Hz panel shows every frame`() {
        assertEquals(600, rendered(steady(59.0, 600), 60f))
    }

    /** Exactly at the panel rate, jitter and all, is still fully showable. */
    @Test
    fun `60fps on a 60Hz panel shows every frame`() {
        assertEquals(300, rendered(steady(60.0, 300), 60f))
    }

    /**
     * A burst — the mechanism that actually saturates the BufferQueue. Ten frames delivered 2 ms
     * apart cannot be shown as ten distinct frames on a 60 Hz panel; showing them all is what jams
     * the codec. Most must be skipped.
     */
    @Test
    fun `a tight burst is paced down instead of flooding the display`() {
        val shown = rendered(List(10) { 2_000_000L }, 60f)
        assertTrue("a 2ms-apart burst of 10 should mostly be skipped, showed $shown", shown <= 3)
        assertTrue("but never all of them skipped, showed $shown", shown >= 1)
    }

    /** A 50 Hz panel (PAL-region sets) must pace against 50, not a hardcoded 60. */
    @Test
    fun `50Hz panel shows every frame of a 48fps sender`() {
        assertEquals(200, rendered(steady(48.0, 200), 50f))
    }

    /**
     * A nonsense refresh rate must not disable the picture. Display.getRefreshRate() is not
     * guaranteed sane, and a 0 would otherwise produce an infinite interval that skips every frame
     * after the first — a black screen from a debug counter.
     */
    @Test
    fun `an implausible refresh rate is clamped rather than blanking the picture`() {
        for (bogus in listOf(0f, -1f, 0.001f, 100_000f)) {
            val shown = rendered(steady(30.0, 60), bogus)
            assertTrue("hz=$bogus starved the display, showed $shown of 60", shown >= 30)
        }
    }

    /** After a stall, the next frame must be shown immediately rather than waiting out a deadline. */
    @Test
    fun `a frame after a long gap is always shown`() {
        val shown = rendered(listOf(16_000_000L, 500_000_000L, 16_000_000L), 60f)
        assertEquals(3, shown)
    }
}
