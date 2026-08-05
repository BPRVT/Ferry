package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.waitBudgetMs] — the cap on how long the decoder thread may block
 * inside a single `decodeNalUnit`.
 *
 * WHY THIS MATTERS: this is the guard that 5.5.0 assumed it had and did not. `decodeNalUnit` blocks
 * waiting for a free codec input buffer on the *only* thread that drains the frame queue, so while
 * it waits the reader keeps enqueuing and nothing is removed. A wait long enough to overflow the
 * queue forces `enqueue` to shed a referenced frame, which arms a keyframe resync and stops the
 * picture for up to three seconds — the wait meant to save one frame costs three seconds of them.
 * With the 100 ms keyframe ceiling it was worse still: the overflow landed *during the IDR's own
 * decode call*, so the arming guard refused to let that IDR clear the resync.
 *
 * The asymmetry that matters here: a budget that is too small costs one dropped frame, a budget
 * that is too large costs a multi-second freeze. These tests lean on that — the important
 * assertions are the upper bounds.
 */
class MirrorWaitBudgetTest {

    private val capacity = 16

    /** An empty queue has nothing behind this frame, so the full ceiling must remain available. */
    @Test
    fun `empty queue affords the full keyframe ceiling`() {
        val budget = MirrorStreamServer.waitBudgetMs(queueDepth = 0, capacity = capacity)
        // 100 ms is VideoDecoder.KEYFRAME_INPUT_BUFFER_WAIT_MS — the largest wait ever requested.
        assertTrue(
            "an empty queue must not restrict the keyframe wait, got ${budget}ms",
            budget >= 100L
        )
    }

    /**
     * The failure that produced the 5.5.0 freeze: a queue this full cannot absorb another 100 ms of
     * reader output, so the keyframe wait must not be granted.
     */
    @Test
    fun `nearly full queue refuses to wait at all`() {
        for (depth in (capacity - 4)..capacity) {
            assertEquals(
                "depth $depth of $capacity has no headroom left to spend",
                0L,
                MirrorStreamServer.waitBudgetMs(depth, capacity)
            )
        }
    }

    /**
     * The bound this whole mechanism exists to enforce: the wait must never outlast the time the
     * reader needs to fill the remaining headroom, or the queue overflows during the wait.
     *
     * Checked at every depth rather than at a chosen one, because the cascade only needs a single
     * depth where the arithmetic is wrong.
     */
    @Test
    fun `budget never exceeds the time the remaining headroom can cover`() {
        val frameIntervalMs = 16L        // nominal 60 fps reader
        for (depth in 0..capacity) {
            val budget = MirrorStreamServer.waitBudgetMs(depth, capacity)
            val headroomMs = (capacity - depth) * frameIntervalMs
            assertTrue(
                "depth $depth: budget ${budget}ms would outlast ${headroomMs}ms of headroom",
                budget < headroomMs || headroomMs == 0L && budget == 0L
            )
        }
    }

    /** Monotonic: a fuller queue can never buy a longer wait than an emptier one. */
    @Test
    fun `budget never increases as the queue fills`() {
        var previous = Long.MAX_VALUE
        for (depth in 0..capacity) {
            val budget = MirrorStreamServer.waitBudgetMs(depth, capacity)
            assertTrue(
                "depth $depth returned ${budget}ms, more than ${previous}ms at depth ${depth - 1}",
                budget <= previous
            )
            previous = budget
        }
    }

    /** Depth may briefly exceed capacity between a size() read and an offer; must not go negative. */
    @Test
    fun `over-full queue clamps to zero rather than going negative`() {
        assertEquals(0L, MirrorStreamServer.waitBudgetMs(capacity + 8, capacity))
    }
}
