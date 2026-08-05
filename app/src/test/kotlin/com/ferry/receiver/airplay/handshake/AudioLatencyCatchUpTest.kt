package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AudioStreamServer.shouldCatchUp] — the controller that drains accumulated audio
 * latency.
 *
 * WHY THIS MATTERS: the audio jitter queue is a ratchet. The producer delivers at the sender's
 * real-time rate and the consumer writes with `WRITE_BLOCKING`, which paces at the DAC's real-time
 * rate, so whatever depth the queue reaches it *keeps* — and the depth is exactly how far audio lags
 * video. Reported from hardware after a link recovery: 27 of 32 packets queued, roughly 290 ms
 * behind the picture, staying there indefinitely because nothing ever drained it.
 *
 * The controller answers that by playing ~2% fast until the backlog clears. The asymmetry that
 * shapes these tests: engaging when it is not needed is nearly free (an inaudible pitch nudge for a
 * few seconds), but **flapping** in and out on every packet would modulate the pitch continuously,
 * which is an audible warble — the exact artifact this design exists to avoid. The hysteresis
 * carries that weight, so most of what follows is about the band between the two thresholds.
 */
class AudioLatencyCatchUpTest {

    /** A healthy stream sits a packet or two deep and must never be touched. */
    @Test
    fun `a shallow queue does not engage catch-up`() {
        for (depth in 0..3) {
            assertFalse(
                "depth $depth is normal operation, not a backlog",
                AudioStreamServer.shouldCatchUp(depth, currentlyCatchingUp = false)
            )
        }
    }

    /** The reported failure: a deep queue is accumulated lag and must drain. */
    @Test
    fun `a deep queue engages catch-up`() {
        for (depth in listOf(12, 20, 27, 32)) {
            assertTrue(
                "depth $depth is ~${depth * 10}ms of audio behind the video",
                AudioStreamServer.shouldCatchUp(depth, currentlyCatchingUp = false)
            )
        }
    }

    /**
     * The load-bearing property. Inside the band, the answer must depend on what the controller was
     * already doing — otherwise a queue hovering near a single threshold would toggle the playback
     * rate packet by packet, at ~90 packets a second.
     */
    @Test
    fun `the band between the thresholds is decided by hysteresis, not by depth alone`() {
        for (depth in 5..11) {
            assertFalse(
                "depth $depth must not start a drain",
                AudioStreamServer.shouldCatchUp(depth, currentlyCatchingUp = false)
            )
            assertTrue(
                "depth $depth must not abandon a drain already under way",
                AudioStreamServer.shouldCatchUp(depth, currentlyCatchingUp = true)
            )
        }
    }

    /** Draining continues until the queue is genuinely back to target, not merely improving. */
    @Test
    fun `catch-up disengages only at target`() {
        assertTrue(AudioStreamServer.shouldCatchUp(5, currentlyCatchingUp = true))
        assertFalse(AudioStreamServer.shouldCatchUp(4, currentlyCatchingUp = true))
        assertFalse(AudioStreamServer.shouldCatchUp(0, currentlyCatchingUp = true))
    }

    /**
     * Simulates a real drain and asserts the rate is switched exactly twice — on and off.
     *
     * A controller can satisfy every threshold test above and still chatter across a realistic
     * trajectory, which is what would actually be audible.
     */
    @Test
    fun `a full drain toggles the playback rate exactly twice`() {
        // Burst to 27, drain steadily, then idle around a shallow depth with ordinary jitter.
        val trajectory = (listOf(1, 2, 27) + (27 downTo 0) + listOf(2, 5, 3, 6, 2, 4, 1)).toList()
        var catching = false
        var transitions = 0
        for (depth in trajectory) {
            val next = AudioStreamServer.shouldCatchUp(depth, catching)
            if (next != catching) transitions++
            catching = next
        }
        assertTrue("expected exactly 2 rate changes, got $transitions", transitions == 2)
        assertFalse("should finish at nominal speed", catching)
    }

    /**
     * Ordinary jitter below the high-water mark must never engage it at all — otherwise the pitch
     * would be nudged during completely healthy playback.
     */
    @Test
    fun `normal jitter never engages catch-up`() {
        var catching = false
        for (depth in listOf(0, 3, 1, 5, 2, 7, 4, 2, 6, 3, 0, 8, 2)) {
            catching = AudioStreamServer.shouldCatchUp(depth, catching)
            assertFalse("depth $depth engaged a drain during healthy playback", catching)
        }
    }
}
