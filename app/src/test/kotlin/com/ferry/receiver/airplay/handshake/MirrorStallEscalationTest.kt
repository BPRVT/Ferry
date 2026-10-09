package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.shouldRebuildAfterStall] — when repeated decoder rebuilds should
 * stop.
 *
 * ── The failure this exists for ──
 *
 * Captured on hardware: an iPad video paused for several minutes and resumed. Audio came back, the
 * picture stayed frozen on the paused frame, and the stall watchdog rebuilt the decoder **179 times,
 * once a second, for seven minutes**.
 *
 * None of those rebuilds could have worked. A freshly built H.264 decoder has no reference picture,
 * so it produces nothing at all until an IDR arrives, and each rebuild threw away the decoder that
 * would otherwise have been waiting for one.
 *
 * 7.7.0 through 7.9.0 escalated to ending the session at this point. That turned out to cost the
 * whole cast whenever it was wrong — and it was wrong on every pause — so as of 8.0.0 the watchdog
 * never ends a session. Past the limit it simply stops rebuilding and leaves the current decoder to
 * resync on the sender's next keyframe.
 */
class MirrorStallEscalationTest {

    /** A rebuild may genuinely need a moment — a keyframe a second away, the codec settling. */
    @Test
    fun `rebuilds up to the limit are allowed`() {
        for (attempt in 1..MirrorStreamServer.STALL_REBUILD_LIMIT) {
            assertTrue(
                "attempt $attempt was refused too early",
                MirrorStreamServer.shouldRebuildAfterStall(attempt)
            )
        }
    }

    /** Past the limit, rebuilding has demonstrably failed and the watchdog stands down. */
    @Test
    fun `rebuilding stops once the limit is spent`() {
        assertFalse(MirrorStreamServer.shouldRebuildAfterStall(MirrorStreamServer.STALL_REBUILD_LIMIT + 1))
        assertFalse(
            "the captured failure ran 179 rebuilds — that must be impossible now",
            MirrorStreamServer.shouldRebuildAfterStall(179)
        )
    }
}
