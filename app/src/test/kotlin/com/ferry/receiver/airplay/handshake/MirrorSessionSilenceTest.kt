package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.isSessionSilent] — the trigger for the failure captured on hardware:
 * the picture frozen, the iPad still playing, and the data socket still open.
 *
 * Neither older rule could fire there. `isStreamDead` waits for the socket to close and it never
 * did; `isStalled` deliberately refuses to judge when nothing is arriving, because iOS sends video
 * only when the screen changes and a paused sender is otherwise indistinguishable from a dead one.
 *
 * Realtime mirroring audio is what breaks the tie: unlike video it is not event-driven, so it keeps
 * arriving at ~92 packets a second for as long as the session lives. These tests pin the cases where
 * that reasoning holds — and, more importantly, the ones where it does not, because this ends live
 * sessions.
 */
class MirrorSessionSilenceTest {

    private val now = 1_000_000L

    /** The captured incident: both streams stopped together, socket still open. */
    @Test
    fun `both streams silent past the threshold is a dead session`() {
        assertTrue(
            MirrorStreamServer.isSessionSilent(
                nowMs = now,
                audioLastArrivalMs = now - 9_000,
                videoLastArrivalMs = now - 9_000,
            )
        )
    }

    /**
     * The case that must never fire, and the reason 6.7.0 refused to use a timeout at all: a static
     * screen produces no video for minutes while the session is perfectly healthy. Audio still
     * flowing is proof of exactly that.
     */
    @Test
    fun `a static screen with audio still arriving is healthy`() {
        assertFalse(
            "audio is the heartbeat — video silence alone means nothing",
            MirrorStreamServer.isSessionSilent(
                nowMs = now,
                audioLastArrivalMs = now - 100,
                videoLastArrivalMs = now - 120_000,
            )
        )
    }

    /** Audio can lag briefly without the session being gone; both halves must agree. */
    @Test
    fun `a brief audio gap while video flows is not silence`() {
        assertFalse(
            MirrorStreamServer.isSessionSilent(
                nowMs = now,
                audioLastArrivalMs = now - 9_000,
                videoLastArrivalMs = now - 200,
            )
        )
    }

    /**
     * No audio stream means no heartbeat, and this must return false rather than guess. A
     * video-only session falls back to the socket-close rule, which is where it was before.
     */
    @Test
    fun `without an audio stream there is no verdict to give`() {
        assertFalse(
            MirrorStreamServer.isSessionSilent(
                nowMs = now,
                audioLastArrivalMs = 0L,
                videoLastArrivalMs = now - 60_000,
            )
        )
    }

    /** Before the first video frame there is nothing to call dead — the session is still starting. */
    @Test
    fun `a session that has not started yet is not silent`() {
        assertFalse(
            MirrorStreamServer.isSessionSilent(
                nowMs = now,
                audioLastArrivalMs = now - 9_000,
                videoLastArrivalMs = 0L,
            )
        )
    }

    /** Just under the threshold must hold, so ordinary jitter never costs a reconnection. */
    @Test
    fun `just under the threshold is not yet a verdict`() {
        assertFalse(
            MirrorStreamServer.isSessionSilent(
                nowMs = now,
                audioLastArrivalMs = now - 7_000,
                videoLastArrivalMs = now - 7_000,
            )
        )
    }
}
