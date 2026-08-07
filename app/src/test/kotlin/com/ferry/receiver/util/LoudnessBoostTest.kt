package com.ferry.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [LoudnessBoost]'s attach policy.
 *
 * The thing being pinned is a **rate**, not a result. `sync` is called once per audio packet — about
 * 92 times a second — on the playback thread, on the explicit promise that it costs nothing unless
 * the request changed. Its early return tests `appliedDb` against the request, so any failure to
 * attach left the two permanently unequal and every subsequent packet retried the whole thing:
 * a binder call into audioserver, an exception, and a log line, ~92 times a second, forever.
 *
 * It could only happen with the boost turned **on** — at 0 dB the request and `appliedDb` agree —
 * so the default configuration never showed it.
 */
class LoudnessBoostTest {

    /** Counts attach attempts and can be told to refuse, standing in for a device without the effect. */
    private class FakeEffects(var failOnOpen: Boolean = false, var failOnGain: Boolean = false) {
        var opens = 0
        var gainCalls = 0
        var closes = 0
        var lastMillibels = -1

        fun open(@Suppress("UNUSED_PARAMETER") sessionId: Int): LoudnessBoost.Effect {
            opens++
            if (failOnOpen) throw UnsupportedOperationException("no effects framework")
            return object : LoudnessBoost.Effect {
                override fun setGainMillibels(millibels: Int) {
                    gainCalls++
                    if (failOnGain) throw IllegalStateException("effect slots exhausted")
                    lastMillibels = millibels
                }
                override fun enable() {}
                override fun close() { closes++ }
            }
        }
    }

    @Test
    fun `a device that refuses the effect is asked once, not once per packet`() {
        val fake = FakeEffects(failOnOpen = true)
        val boost = LoudnessBoost(fake::open)

        repeat(500) { boost.sync(SESSION, 12) }

        assertEquals("the refused attach must not be retried per packet", 1, fake.opens)
    }

    @Test
    fun `an effect that refuses the gain is not retried per packet either`() {
        val fake = FakeEffects(failOnGain = true)
        val boost = LoudnessBoost(fake::open)

        repeat(500) { boost.sync(SESSION, 12) }

        assertEquals(1, fake.gainCalls)
        assertEquals("a useless effect must not be left in the output path", 1, fake.closes)
    }

    @Test
    fun `a successful attach also happens once, and applies the right gain`() {
        val fake = FakeEffects()
        val boost = LoudnessBoost(fake::open)

        repeat(500) { boost.sync(SESSION, 12) }

        assertEquals(1, fake.opens)
        assertEquals(1, fake.gainCalls)
        assertEquals("12 dB is 1200 millibels", 1200, fake.lastMillibels)
    }

    /**
     * A new AudioTrack is a genuinely new attempt: the effect is attached per session, and the usual
     * cause of a refusal (the device's global effect-slot budget) may have cleared since.
     */
    @Test
    fun `a new audio session retries an attach that failed on the old one`() {
        val fake = FakeEffects(failOnOpen = true)
        val boost = LoudnessBoost(fake::open)

        repeat(100) { boost.sync(SESSION, 12) }
        repeat(100) { boost.sync(SESSION + 1, 12) }

        assertEquals("one attempt per session, not one per packet", 2, fake.opens)
    }

    /** Changing the slider is a new request, so a failure at one gain must not mute another. */
    @Test
    fun `changing the requested gain retries`() {
        val fake = FakeEffects(failOnOpen = true)
        val boost = LoudnessBoost(fake::open)

        repeat(100) { boost.sync(SESSION, 12) }
        repeat(100) { boost.sync(SESSION, 6) }

        assertEquals(2, fake.opens)
    }

    /** The default: 0 dB never attaches anything, so it never reaches the failure path at all. */
    @Test
    fun `boost off never opens an effect`() {
        val fake = FakeEffects(failOnOpen = true)
        val boost = LoudnessBoost(fake::open)

        repeat(500) { boost.sync(SESSION, 0) }

        assertEquals(0, fake.opens)
    }

    /** Turning it off after it is on takes the effect back out of the output path. */
    @Test
    fun `turning the boost off releases the effect`() {
        val fake = FakeEffects()
        val boost = LoudnessBoost(fake::open)

        boost.sync(SESSION, 12)
        boost.sync(SESSION, 0)

        assertEquals(1, fake.closes)
    }

    private companion object {
        /** Any positive value; real AudioTrack session ids are never zero. */
        const val SESSION = 42
    }
}
