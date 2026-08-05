package com.ferry.receiver.util

/**
 * RenderPacer — decides whether a decoded video frame should be shown, or handed back unshown.
 *
 * Lives in `util` rather than beside the decoder for the same reason as [VideoFit]: it is pure
 * arithmetic over a clock, with no Android types, so it is unit-testable from both the app module
 * and the standalone `test-runner` — which shadows `VideoDecoder` with a stub and could not reach
 * this rule if it lived there. [com.ferry.receiver.airplay.VideoDecoder] is then only responsible
 * for reading the clock and calling `releaseOutputBuffer`.
 *
 * ── The problem this exists to solve ──
 *
 * Ferry used to show every frame it decoded. That sounds like the conservative choice and it is the
 * opposite, because of how a SurfaceView returns buffers. `releaseOutputBuffer(index, true)` hands
 * the buffer to the display's BufferQueue, which holds only about three, and the codec does not get
 * it back until the display has consumed it. Show frames faster than the panel refreshes — which is
 * what happens when an iPad's mirror rate jumps from 24 fps to ~59 on a 60 Hz TV — and the queue
 * saturates. A codec with no free output buffer stops decoding, and a codec that has stopped
 * decoding stops handing back **input** buffers. `VideoDecoder.decodeNalUnit` then finds none free
 * and destroys the frame.
 *
 * So the jam was on the output side and the loss happened on the input side, which is why the two
 * previous attempts could not have worked: 5.5.0 lengthened the input wait, but no wait frees a
 * buffer the display is holding, and 6.0.0 capped that wait so it could not overflow the frame
 * queue. Both were real bugs; neither was this one.
 *
 * ── The asymmetry that decides the fix ──
 *
 *  - **Not showing a decoded frame** costs one frame nobody can see. It is still decoded, so it
 *    still serves as a reference for every frame that predicts from it.
 *  - **Not decoding a frame** breaks the reference chain. Later frames predict from something the
 *    decoder never had, and the picture stays visibly wrong until the sender's next IDR — around
 *    ten seconds on iOS, longer on a static screen.
 *
 * Ferry was paying the second cost to avoid the first. This pays the first instead, and returns the
 * buffer to the codec immediately, which is what stops the input side starving at all.
 *
 * ── The rule ──
 *
 * Show a frame only once the previously shown one has had its time on screen. Frames arriving
 * sooner cannot be displayed as distinct frames anyway — the panel has not refreshed — so showing
 * them only queues them behind the one already there.
 *
 * At 24 fps into a 60 Hz panel this never triggers (41 ms apart against a 16.7 ms interval). At a
 * steady 59 fps it still never triggers, because the panel really can show 59 distinct frames. It
 * triggers on **bursts**, which is exactly the case that was breaking.
 */
object RenderPacer {

    /**
     * Fraction of a refresh interval a frame may arrive early and still be shown.
     *
     * The tolerance exists because the two clocks are independent and nearly identical in the case
     * that matters: 59 fps arrivals are 16.95 ms apart and a 60 Hz panel refreshes every 16.67 ms.
     * Without slack, ordinary jitter would push an arrival a fraction under the interval and skip a
     * frame the display was perfectly able to show — visible judder, to fix a problem that was not
     * occurring.
     *
     * 20% of 16.7 ms is about 3.3 ms: comfortably more than that jitter, and well under the 8.3 ms
     * that would let two frames genuinely share one refresh.
     */
    const val RENDER_INTERVAL_TOLERANCE = 0.20

    /**
     * Sanity bounds on the reported panel refresh rate.
     *
     * 24 Hz because no television refreshes slower, so a lower reading means the value is wrong;
     * 240 Hz because above that the interval is short enough to be a no-op anyway. These guard
     * against `Display.getRefreshRate()` returning 0 or nonsense, which would otherwise mean an
     * infinite interval that skips every frame after the first — turning a display nicety into a
     * black screen.
     */
    const val MIN_PLAUSIBLE_REFRESH_HZ = 24f
    const val MAX_PLAUSIBLE_REFRESH_HZ = 240f

    /**
     * May a frame arriving at [nowNs] be shown, given the last shown frame was at [lastRenderNs] on
     * a [refreshHz] panel?
     *
     * @param lastRenderNs 0 when nothing has been shown yet, which always renders. The first frame
     *   of a session — and of a decoder rebuilt against a new Surface — must never wait out a
     *   deadline it has no basis for.
     */
    fun isDueToRender(nowNs: Long, lastRenderNs: Long, refreshHz: Float): Boolean {
        if (lastRenderNs == 0L) return true
        return nowNs - lastRenderNs >= minRenderIntervalNs(refreshHz)
    }

    /** One display refresh interval, minus [RENDER_INTERVAL_TOLERANCE], in nanoseconds. */
    fun minRenderIntervalNs(refreshHz: Float): Long {
        val hz = refreshHz.coerceIn(MIN_PLAUSIBLE_REFRESH_HZ, MAX_PLAUSIBLE_REFRESH_HZ)
        return ((1_000_000_000.0 / hz) * (1.0 - RENDER_INTERVAL_TOLERANCE)).toLong()
    }
}
