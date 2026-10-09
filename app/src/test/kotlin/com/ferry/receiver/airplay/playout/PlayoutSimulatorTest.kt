package com.ferry.receiver.airplay.playout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bad-Wi-Fi simulator, run against [PlayoutScheduler] with the settings Ferry ships.
 *
 * Each test is a shape of Wi-Fi trouble, played twice: straight through (how Ferry behaved up to
 * 8.0.0) and through the scheduler. The straight-through runs are not there to be beaten for sport
 * — they prove the trace actually contains the problem, so a passing scheduler test means something.
 */
class PlayoutSimulatorTest {

    private fun scheduler() = PlayoutScheduler(minDelayMs = MIN, maxDelayMs = MAX)

    @Test
    fun `good Wi-Fi costs almost no delay and never freezes`() {
        val trace = BadWifi.trace(durationMs = 30_000, jitterMs = 6)
        val straight = BadWifi.replay(trace, null)
        val smoothed = BadWifi.replay(trace, scheduler())
        assertEquals(0, straight.freezes)
        assertEquals(0, smoothed.freezes)
        assertTrue("delay on a clean link was ${smoothed.finalDelayMs}ms", smoothed.finalDelayMs <= MIN + 40)
    }

    @Test
    fun `a repeat stall is hidden once the link has shown it drops`() {
        val stalls = listOf(BadWifi.Stall(5_000, 400), BadWifi.Stall(10_000, 400), BadWifi.Stall(15_000, 400))
        val trace = BadWifi.trace(durationMs = 20_000, jitterMs = 4, stalls = stalls)

        val straight = BadWifi.replay(trace, null)
        assertEquals("the trace must actually freeze without buffering", 3, straight.freezes)
        assertTrue("and fast-forward after each stall", straight.rushedFrames > 30)

        val smoothed = BadWifi.replay(trace, scheduler())
        // The first stall cannot be hidden: nothing has warned the scheduler yet. Every one after it
        // must be.
        assertTrue("froze ${smoothed.freezes} times", smoothed.freezes <= 1)
        assertEquals("no fast-forward after stalls", 0, smoothed.rushedFrames)
    }

    @Test
    fun `a link that keeps dropping stays smooth`() {
        val stalls = (1..20).map { BadWifi.Stall(it * 2_500L, 250) }
        val trace = BadWifi.trace(durationMs = 55_000, jitterMs = 15, stalls = stalls, seed = 7)
        val straight = BadWifi.replay(trace, null)
        val smoothed = BadWifi.replay(trace, scheduler())
        assertEquals(20, straight.freezes)
        assertTrue("froze ${smoothed.freezes} times out of 20 stalls", smoothed.freezes <= 1)
    }

    @Test
    fun `random jitter is absorbed`() {
        val trace = BadWifi.trace(durationMs = 30_000, jitterMs = 150, seed = 3)
        val straight = BadWifi.replay(trace, null)
        val smoothed = BadWifi.replay(trace, scheduler())
        assertTrue("straight-through should stutter (${straight.freezes})", straight.freezes > 0)
        assertEquals(0, smoothed.freezes)
        assertTrue(smoothed.finalDelayMs in 150..250)
    }

    @Test
    fun `delay comes back down once the Wi-Fi settles`() {
        val trace = BadWifi.trace(durationMs = 70_000, jitterMs = 4, stalls = listOf(BadWifi.Stall(5_000, 600)))
        val smoothed = BadWifi.replay(trace, scheduler())
        assertTrue("peak ${smoothed.peakDelayMs}ms should cover the 600ms stall", smoothed.peakDelayMs >= 600)
        assertTrue("delay stuck at ${smoothed.finalDelayMs}ms long after the link recovered",
            smoothed.finalDelayMs <= MIN + 40)
    }

    @Test
    fun `delay never exceeds the ceiling, however bad the stall`() {
        val trace = BadWifi.trace(durationMs = 20_000, stalls = listOf(BadWifi.Stall(5_000, 4_000)))
        val smoothed = BadWifi.replay(trace, scheduler())
        assertTrue(smoothed.peakDelayMs <= MAX)
    }

    @Test
    fun `a long outage restarts the estimate instead of pinning the delay`() {
        val s = scheduler()
        val trace = BadWifi.trace(durationMs = 40_000, stalls = listOf(BadWifi.Stall(5_000, 15_000)))
        BadWifi.replay(trace, s)
        assertTrue("a 15s outage should reset, not count as lateness", s.resets >= 1)
        assertTrue(s.targetDelayMs <= MIN + 40)
    }

    @Test
    fun `a paused or static screen is not mistaken for bad Wi-Fi`() {
        // iOS sends nothing at all while the picture does not change. Minutes of that is normal.
        val trace = BadWifi.trace(durationMs = 200_000, jitterMs = 4, idle = listOf(10_000L until 190_000L))
        val s = scheduler()
        val smoothed = BadWifi.replay(trace, s)
        assertEquals(0, smoothed.freezes)
        assertEquals(0, s.resets)
        assertTrue(smoothed.finalDelayMs <= MIN + 40)
    }

    @Test
    fun `slow drift between the two clocks does not build up delay`() {
        // Sender clock running 0.1% slow: every frame looks a hair later than the last.
        val drifting = BadWifi.trace(durationMs = 120_000, jitterMs = 4)
            .map { it.copy(arrivalMs = it.arrivalMs + it.senderMs.minus(3_600_000) / 1000) }
        val smoothed = BadWifi.replay(drifting, scheduler())
        assertTrue("drift accumulated into ${smoothed.finalDelayMs}ms of delay", smoothed.finalDelayMs <= MIN + 80)
    }

    @Test
    fun `a trace captured on the TV replays exactly`() {
        val stalls = listOf(BadWifi.Stall(3_000, 300), BadWifi.Stall(6_000, 300))
        val original = BadWifi.trace(durationMs = 10_000, jitterMs = 20, stalls = stalls, seed = 11)
        NetworkTrace.clear()
        original.forEach { NetworkTrace.record(it.senderMs, it.arrivalMs, it.bytes, it.keyframe) }
        val parsed = NetworkTrace.parse(NetworkTrace.csv())
        NetworkTrace.clear()
        assertEquals(original, parsed)
        assertEquals(BadWifi.replay(original, scheduler()), BadWifi.replay(parsed, scheduler()))
    }

    private companion object {
        // What MirrorStreamServer uses with "Smooth playback" on.
        const val MIN = 60L
        const val MAX = 1_000L
    }
}
