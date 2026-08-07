package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.shouldRecycleAfterStall] — when repeated decoder rebuilds should
 * give up and end the session instead.
 *
 * ── The failure this exists for ──
 *
 * Captured on hardware: an iPad video paused for several minutes and resumed. Audio came back, the
 * picture stayed frozen on the paused frame, and the stall watchdog rebuilt the decoder **179 times,
 * once a second, for seven minutes**, without ever escalating.
 *
 * None of those rebuilds could have worked. A freshly built H.264 decoder has no reference picture,
 * so it produces nothing at all until an IDR arrives — and on a resume iOS may not send one for a
 * very long time. Every frame arriving meanwhile is a P-frame predicting from pictures that decoder
 * never had. So each rebuild produced a decoder in exactly the state that cannot recover, and the
 * next tick built another one.
 *
 * A rebuild that produced no frame is evidence the remedy is wrong, not an attempt needing more
 * patience. Ending the session is the only remedy that reaches the cause, because a fresh SETUP
 * makes the sender start a new stream — and a new stream begins with a keyframe.
 */
class MirrorStallEscalationTest {

    private val now = 10_000_000L

    /** One or two failed rebuilds are ordinary; a keyframe may genuinely be a moment away. */
    @Test
    fun `a few failed rebuilds do not end the session`() {
        for (attempt in 1..14) {
            assertFalse(
                "attempt $attempt escalated too early",
                MirrorStreamServer.shouldRecycleAfterStall(attempt, QUIET, now, lastRecycleMs = 0L)
            )
        }
    }

    /** Past the threshold, rebuilding has demonstrably failed and the session is recycled. */
    @Test
    fun `sustained failed rebuilds end the session`() {
        assertTrue(MirrorStreamServer.shouldRecycleAfterStall(15, QUIET, now, lastRecycleMs = 0L))
        assertTrue(MirrorStreamServer.shouldRecycleAfterStall(179, QUIET, now, lastRecycleMs = 0L))
    }

    /**
     * The guard that stops this becoming worse than the bug. A recycle is a visible reconnection, so
     * a session that keeps going wrong must degrade into a poor picture rather than a reconnect loop
     * — which is precisely the shape of the 179-rebuild failure, one level up.
     */
    @Test
    fun `a recent recycle blocks another one`() {
        assertFalse(
            "recycled 10s ago — must not immediately recycle again",
            MirrorStreamServer.shouldRecycleAfterStall(50, QUIET, now, lastRecycleMs = now - 10_000)
        )
    }

    /** Once the rate limit has elapsed, a session still failing may be recycled again. */
    @Test
    fun `after the rate limit a still-broken session may recycle again`() {
        assertTrue(
            MirrorStreamServer.shouldRecycleAfterStall(15, QUIET, now, lastRecycleMs = now - 61_000)
        )
    }

    /** Zero rebuilds is not a verdict — the counter only advances when one actually failed. */
    @Test
    fun `no failed rebuilds never escalates`() {
        assertFalse(MirrorStreamServer.shouldRecycleAfterStall(0, QUIET, now, lastRecycleMs = 0L))
    }

    /**
     * **The case this gate was added for**, from a captured log. Audio resend requests went from 1
     * to 64 in ten seconds — a Wi-Fi storm — the picture froze, and ten seconds later Ferry killed a
     * session that might well have recovered once the link settled.
     *
     * A frozen picture on a *struggling* link is a symptom. A frozen picture on a *quiet* link is a
     * wedged decoder. Only the second is something a new session can fix, and ending the session is
     * now known not to reliably bring the sender back — so a mistake here costs the whole cast.
     */
    @Test
    fun `a link fighting to stay up is never torn down, however long the freeze`() {
        for (rebuilds in listOf(15, 50, 179)) {
            assertFalse(
                "recycled during a network storm after $rebuilds rebuilds",
                MirrorStreamServer.shouldRecycleAfterStall(rebuilds, STORM, now, lastRecycleMs = 0L)
            )
        }
    }

    /** Occasional loss is not a storm — a wedged decoder on a nearly-quiet link still escalates. */
    @Test
    fun `light packet loss does not block escalation`() {
        assertTrue(MirrorStreamServer.shouldRecycleAfterStall(15, 2, now, lastRecycleMs = 0L))
    }

    /** Patience is deliberately long: a wedged decoder is not time-limited, a network event is. */
    @Test
    fun `escalation waits far longer than it used to`() {
        assertFalse(
            "five rebuilds used to be enough, and killed a session mid-storm",
            MirrorStreamServer.shouldRecycleAfterStall(5, QUIET, now, lastRecycleMs = 0L)
        )
    }

    private companion object {
        /** A healthy link: no resend requests at all in the last second. */
        const val QUIET = 0

        /** The captured storm ran at roughly 6-13 resend requests a second. */
        const val STORM = 8
    }
}
