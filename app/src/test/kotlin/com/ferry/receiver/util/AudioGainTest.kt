package com.ferry.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for [AudioGain] — the AirPlay-dB → linear-amplitude conversion.
 *
 * The bug these lock down: the conversion used to be a linear remap of the dB range onto 0..1,
 * which is correct only at the two endpoints and roughly 9 dB too loud in the middle. Several
 * cases below fail against that old formula, which is the point of them.
 */
class AudioGainTest {

    /** Amplitude ratios are compared loosely — this is float audio gain, not exact arithmetic. */
    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.005f) {
        assertTrue(
            "expected ~$expected but was $actual",
            abs(expected - actual) <= tolerance
        )
    }

    // ─── The endpoints, which the old linear map also got right ──────────────

    @Test
    fun `full volume is unity gain`() {
        assertClose(1.0f, AudioGain.amplitudeFor(0f))
    }

    @Test
    fun `the mute sentinel is silence, not a quiet level`() {
        assertEquals(0f, AudioGain.amplitudeFor(-144f))
        assertEquals(0f, AudioGain.amplitudeFor(-200f))
    }

    // ─── The middle, which it did not ────────────────────────────────────────

    @Test
    fun `mid-slider is a true minus 15 dB, not the old 0 point 5`() {
        // 10^(-15/20) = 0.1778. The old linear map produced 0.5 here — about +9 dB too loud.
        assertClose(0.178f, AudioGain.amplitudeFor(-15f))
    }

    @Test
    fun `quarter-slider is a true minus 22 point 5 dB`() {
        assertClose(0.075f, AudioGain.amplitudeFor(-22.5f))
    }

    @Test
    fun `three-quarter slider is a true minus 7 point 5 dB`() {
        assertClose(0.422f, AudioGain.amplitudeFor(-7.5f))
    }

    @Test
    fun `halving the amplitude takes about 6 dB`() {
        // The defining property of the scale: −6 dB is half amplitude. A linear map has no such
        // property anywhere, so this is the cleanest statement of what was wrong.
        val full = AudioGain.amplitudeFor(0f)
        val minus6 = AudioGain.amplitudeFor(-6.02f)
        assertClose(full / 2f, minus6)
    }

    // ─── Range handling ──────────────────────────────────────────────────────

    @Test
    fun `the quietest normal level is audible rather than silent`() {
        val quietest = AudioGain.amplitudeFor(-30f)
        assertTrue("−30 dB should be quiet but not silent, was $quietest", quietest > 0f)
        assertClose(0.032f, quietest)
    }

    @Test
    fun `below the floor clamps to the floor instead of muting`() {
        // Deliberate: a sender reporting slightly out of range should go quiet, not cut out.
        assertEquals(AudioGain.amplitudeFor(-30f), AudioGain.amplitudeFor(-40f))
    }

    @Test
    fun `above full volume clamps to unity`() {
        assertClose(1.0f, AudioGain.amplitudeFor(6f))
    }

    @Test
    fun `gain rises monotonically across the whole slider`() {
        var previous = -1f
        var db = -30f
        while (db <= 0f) {
            val gain = AudioGain.amplitudeFor(db)
            assertTrue("gain should increase with dB; $db gave $gain after $previous", gain > previous)
            previous = gain
            db += 0.5f
        }
    }

    // ─── Boost clamping ──────────────────────────────────────────────────────

    @Test
    fun `boost clamps to the supported range`() {
        assertEquals(0, AudioGain.clampBoostDb(-5))
        assertEquals(0, AudioGain.clampBoostDb(0))
        assertEquals(6, AudioGain.clampBoostDb(6))
        assertEquals(AudioGain.MAX_BOOST_DB, AudioGain.clampBoostDb(99))
    }

    @Test
    fun `every offered boost step is within the supported range`() {
        AudioGain.BOOST_STEPS.forEach { step ->
            assertEquals("step $step should survive clamping", step, AudioGain.clampBoostDb(step))
        }
    }

    @Test
    fun `the boost steps start at off and end at the maximum`() {
        assertEquals(0, AudioGain.BOOST_STEPS.first())
        assertEquals(AudioGain.MAX_BOOST_DB, AudioGain.BOOST_STEPS.last())
    }
}
