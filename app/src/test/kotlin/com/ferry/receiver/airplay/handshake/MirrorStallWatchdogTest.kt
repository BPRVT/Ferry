package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.isStalled] — the watchdog's decision to force a decoder rebuild.
 *
 * WHY THIS MATTERS: a frozen picture used to be permanent. Reported from hardware on 6.0.0 — the
 * iPad kept playing, TV audio kept playing, and the video sat on a single frame until the cast was
 * stopped and restarted by hand. Nothing in the video path watched itself, so a decoder that stopped
 * producing simply stayed stopped while the session still reported CONNECTED.
 *
 * The asymmetry here runs opposite to most of this codebase, and it is what the tests are shaped
 * around. A missed stall costs a frozen picture the user can still fix with the remote. A **false**
 * stall tears down a healthy decoder — and because the conditions that produce it would still be
 * true a second later, it would do so again, and again, on a timer. A watchdog that fires wrongly
 * is worse than no watchdog, so the negative cases below carry the weight.
 *
 * The static-screen case is the sharp one: iOS sends frames only when something changes, so a paused
 * video or a still menu legitimately produces no frames and no rendering for minutes.
 */
class MirrorStallWatchdogTest {

    private val t0 = 1_000_000L        // arbitrary epoch base

    /** Healthy: frames arriving and being shown continuously. */
    @Test
    fun `a healthy stream is not stalled`() {
        assertFalse(
            MirrorStreamServer.isStalled(
                nowMs = t0 + 60_000,
                firstArrivalMs = t0,
                lastArrivalMs = t0 + 59_980,
                lastShownMs = t0 + 59_950,
            )
        )
    }

    /**
     * The case that would make this mechanism harmful. Nothing has been shown for a full minute, but
     * nothing has arrived either — that is a paused iPad, not a fault. Rebuilding here would thrash
     * a perfectly good decoder for as long as the user left the screen still.
     */
    @Test
    fun `a static screen is never a stall no matter how long`() {
        for (idleMs in listOf(5_000L, 30_000L, 300_000L)) {
            assertFalse(
                "idle for ${idleMs}ms with no arrivals must not count as a stall",
                MirrorStreamServer.isStalled(
                    nowMs = t0 + idleMs,
                    firstArrivalMs = t0,
                    lastArrivalMs = t0,          // nothing new has come in
                    lastShownMs = t0,
                )
            )
        }
    }

    /** The reported failure: frames still arriving, nothing reaching the screen. */
    @Test
    fun `frames arriving with nothing shown is a stall`() {
        assertTrue(
            MirrorStreamServer.isStalled(
                nowMs = t0 + 30_000,
                firstArrivalMs = t0,
                lastArrivalMs = t0 + 29_900,     // still flowing
                lastShownMs = t0 + 10_000,       // but frozen for 20s
            )
        )
    }

    /**
     * A keyframe resync legitimately shows nothing while frames arrive, for up to
     * RESYNC_GIVE_UP_MS (3s). Firing inside that window would abort a recovery that was working
     * exactly as designed — and would keep doing it.
     */
    @Test
    fun `a legitimate keyframe resync is not treated as a stall`() {
        assertFalse(
            "3s of skipping while resyncing is normal, not a fault",
            MirrorStreamServer.isStalled(
                nowMs = t0 + 3_000,
                firstArrivalMs = t0 - 60_000,
                lastArrivalMs = t0 + 2_950,
                lastShownMs = t0,
            )
        )
    }

    /**
     * A stream that arrives but never produces a single frame. Cannot be caught by measuring from
     * the last frame shown, because there isn't one — hence the firstArrival reference.
     */
    @Test
    fun `a stream that never shows anything is caught`() {
        assertTrue(
            MirrorStreamServer.isStalled(
                nowMs = t0 + 20_000,
                firstArrivalMs = t0,
                lastArrivalMs = t0 + 19_900,
                lastShownMs = 0L,                // nothing ever rendered
            )
        )
    }

    /** Before any frame has arrived there is nothing to judge — must not fire on an idle session. */
    @Test
    fun `a session with no arrivals at all is not stalled`() {
        assertFalse(
            MirrorStreamServer.isStalled(
                nowMs = t0 + 60_000,
                firstArrivalMs = 0L,
                lastArrivalMs = 0L,
                lastShownMs = 0L,
            )
        )
    }

    /** A brief hiccup shorter than the deadline must ride out rather than trigger a rebuild. */
    @Test
    fun `a short gap is not a stall`() {
        assertFalse(
            MirrorStreamServer.isStalled(
                nowMs = t0 + 2_000,
                firstArrivalMs = t0 - 60_000,
                lastArrivalMs = t0 + 1_950,
                lastShownMs = t0,
            )
        )
    }
}
