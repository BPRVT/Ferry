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

    // ─── isStreamDead: the video connection has gone, so the session must end ─────────────────
    //
    // Reported from hardware on 6.6.0, with the overlay open: `in 38s`, `last 38s`, `DEC ok`,
    // `qdrop 0%`, `q 2/16`. The decoder was healthy and had dropped four frames all session — it
    // simply had nothing to decode, because the sender's data connection had gone and Ferry had no
    // way to notice or recover. Meanwhile the iPad went on playing and the session still reported
    // CONNECTED to everyone.

    /** The reported failure: connected, then the socket closed and stayed closed. */
    @Test
    fun `a closed data connection ends the session`() {
        assertTrue(
            MirrorStreamServer.isStreamDead(
                nowMs = t0 + 10_000,
                everConnected = true,
                dataConnected = false,
                dataClosedAtMs = t0,
            )
        )
    }

    /** A live connection is never dead, however long it has been quiet. */
    @Test
    fun `a live data connection is never declared dead`() {
        assertFalse(
            MirrorStreamServer.isStreamDead(
                nowMs = t0 + 600_000,
                everConnected = true,
                dataConnected = true,
                dataClosedAtMs = 0L,
            )
        )
    }

    /**
     * The case that makes the narrow trigger worth its narrowness.
     *
     * A paused iPad sends no frames at all, indefinitely, over a perfectly healthy connection. Had
     * this rule keyed on "no frames for N seconds" instead of on the socket actually closing, every
     * pause would have torn down a working cast — a far worse bug than the freeze being fixed, and
     * the exact trap the [isStalled] rule above also had to avoid.
     */
    @Test
    fun `a paused sender with a live connection is not a dead stream`() {
        for (quietMs in listOf(10_000L, 120_000L, 3_600_000L)) {
            assertFalse(
                "quiet for ${quietMs}ms on a live connection is a paused sender, not a fault",
                MirrorStreamServer.isStreamDead(
                    nowMs = t0 + quietMs,
                    everConnected = true,
                    dataConnected = true,
                    dataClosedAtMs = 0L,
                )
            )
        }
    }

    /**
     * The same rule, isolated so it actually pins the live-connection check.
     *
     * The test above passes even with that check deleted, because its `dataClosedAtMs = 0` is caught
     * by a later guard — mutation testing found that hole rather than reading did. Here the close
     * timestamp is old and non-zero, so a live connection is the *only* thing standing between this
     * and a wrongly-declared death.
     */
    @Test
    fun `a live connection outranks a stale close timestamp`() {
        assertFalse(
            "dataConnected must win over an old dataClosedAtMs",
            MirrorStreamServer.isStreamDead(
                nowMs = t0 + 600_000,
                everConnected = true,
                dataConnected = true,
                dataClosedAtMs = t0,      // closed once, long ago, but currently connected
            )
        )
    }

    /** Before the sender has ever connected, "disconnected" is just the starting state. */
    @Test
    fun `never having connected is not a dead stream`() {
        assertFalse(
            MirrorStreamServer.isStreamDead(
                nowMs = t0 + 600_000,
                everConnected = false,
                dataConnected = false,
                dataClosedAtMs = 0L,
            )
        )
    }

    /** Inside the grace period, an orderly teardown must not be reported as a failure. */
    @Test
    fun `a just-closed connection waits out the grace period`() {
        assertFalse(
            MirrorStreamServer.isStreamDead(
                nowMs = t0 + 500,
                everConnected = true,
                dataConnected = false,
                dataClosedAtMs = t0,
            )
        )
    }
}
