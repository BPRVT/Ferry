package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Test

/** Smooth playback's audio cushion: how deep it is and how the speed steers the queue toward it. */
class AudioSmoothPlaybackTest {

    @Test
    fun `cushion depth rounds up to whole packets`() {
        // AAC-ELD at 44.1 kHz: 480 samples ≈ 10.9 ms per packet.
        val eld = 480.0 * 1000 / 44_100
        assertEquals(6, AudioStreamServer.depthFor(60, eld))
        assertEquals(92, AudioStreamServer.depthFor(1_000, eld))
        assertEquals(0, AudioStreamServer.depthFor(0, eld))
    }

    @Test
    fun `on target plays at normal speed`() {
        assertEquals(1.0, AudioStreamServer.smoothRateFor(depth = 20, target = 20, current = 1.0), 0.0)
        assertEquals(1.0, AudioStreamServer.smoothRateFor(depth = 25, target = 20, current = 1.0), 0.0)
        assertEquals(1.0, AudioStreamServer.smoothRateFor(depth = 12, target = 20, current = 1.0), 0.0)
    }

    @Test
    fun `well over target speeds up until the cushion is reached`() {
        var rate = AudioStreamServer.smoothRateFor(depth = 30, target = 20, current = 1.0)
        assertEquals(1.02, rate, 0.0)
        rate = AudioStreamServer.smoothRateFor(depth = 24, target = 20, current = rate)
        assertEquals("keeps draining inside the band", 1.02, rate, 0.0)
        rate = AudioStreamServer.smoothRateFor(depth = 20, target = 20, current = rate)
        assertEquals(1.0, rate, 0.0)
    }

    @Test
    fun `well under target slows down until the cushion has grown`() {
        var rate = AudioStreamServer.smoothRateFor(depth = 5, target = 40, current = 1.0)
        assertEquals(0.98, rate, 0.0)
        rate = AudioStreamServer.smoothRateFor(depth = 30, target = 40, current = rate)
        assertEquals("keeps building inside the band", 0.98, rate, 0.0)
        rate = AudioStreamServer.smoothRateFor(depth = 40, target = 40, current = rate)
        assertEquals(1.0, rate, 0.0)
    }

    @Test
    fun `a tiny cushion never slows playback`() {
        assertEquals(1.0, AudioStreamServer.smoothRateFor(depth = 0, target = 3, current = 1.0), 0.0)
    }
}
