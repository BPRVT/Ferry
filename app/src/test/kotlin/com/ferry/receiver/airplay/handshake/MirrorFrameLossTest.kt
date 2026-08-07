package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.isLosingFrames] — the trigger for automatic mid-session recovery.
 *
 * This decides whether Ferry rebuilds its decoder and, if that does not help, ends the session so
 * the sender re-establishes it. That second step is the automatic equivalent of the user stopping
 * and restarting the share, and it is **destructive**: firing it on a healthy cast interrupts
 * something that was working. So the interesting cases here are not the ones that should trigger,
 * they are the ones that must not.
 */
class MirrorFrameLossTest {

    @Test
    fun `sustained heavy loss is degraded`() {
        assertTrue(MirrorStreamServer.isLosingFrames(arrived = 60, lost = 30))
        assertTrue("exactly at the threshold counts", MirrorStreamServer.isLosingFrames(60, 12))
    }

    @Test
    fun `occasional loss on an otherwise healthy stream is not degraded`() {
        assertFalse(MirrorStreamServer.isLosingFrames(arrived = 60, lost = 5))
        assertFalse(MirrorStreamServer.isLosingFrames(arrived = 60, lost = 0))
    }

    /**
     * The case that would break a working cast. iOS sends frames only when the screen changes, so a
     * still menu or a paused video legitimately produces a trickle — and against a trickle, a single
     * unlucky drop is a huge percentage. Judging that sample at all would mean recycling sessions
     * for the crime of showing a static picture.
     */
    @Test
    fun `a trickle of frames is never judged, however bad the ratio looks`() {
        assertFalse("1 of 3 lost is 33%, on a sample far too small to mean it",
            MirrorStreamServer.isLosingFrames(arrived = 3, lost = 1))
        assertFalse("total loss of a tiny sample is still not a verdict",
            MirrorStreamServer.isLosingFrames(arrived = 2, lost = 2))
        assertFalse(MirrorStreamServer.isLosingFrames(arrived = 0, lost = 0))
    }

    /**
     * A counter that went backwards — which a rebuilt decoder or a wrapped int could produce — must
     * read as "nothing lost", not as a negative that sneaks past a comparison.
     */
    @Test
    fun `negative or impossible deltas are not degraded`() {
        assertFalse(MirrorStreamServer.isLosingFrames(arrived = 60, lost = -5))
        assertFalse(MirrorStreamServer.isLosingFrames(arrived = -1, lost = 10))
    }

    /**
     * Render skips are not loss and are counted elsewhere on purpose — this function only ever sees
     * queue drops and decoder drops. Pinned as a boundary: a full 60 fps second losing 11 frames is
     * under the bar, and one losing 12 is over it.
     */
    @Test
    fun `the boundary sits where the constant says it does`() {
        assertFalse(MirrorStreamServer.isLosingFrames(arrived = 60, lost = 11))
        assertTrue(MirrorStreamServer.isLosingFrames(arrived = 60, lost = 12))
    }
}
