package com.ferry.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFadeTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
        return out
    }

    private fun samples(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map { (pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8) }

    @Test
    fun `fade in starts silent and ramps up`() {
        val p = pcm(1000, 1000, 1000, 1000, 1000, 1000)
        PcmFade.fadeIn(p, p.size, channels = 1, rampFrames = 4)
        assertEquals(listOf(0, 250, 500, 750, 1000, 1000), samples(p))
    }

    @Test
    fun `fade out ends silent`() {
        val p = pcm(-1000, -1000, -1000, -1000)
        PcmFade.fadeOut(p, p.size, channels = 1, rampFrames = 2)
        assertEquals(listOf(-1000, -1000, -500, 0), samples(p))
    }

    @Test
    fun `stereo frames are scaled together`() {
        val p = pcm(800, -800, 800, -800)
        PcmFade.fadeIn(p, p.size, channels = 2, rampFrames = 2)
        assertEquals(listOf(0, 0, 400, -400), samples(p))
    }

    @Test
    fun `only the valid length is touched`() {
        val p = pcm(1000, 1000, 1000, 1000)
        PcmFade.fadeOut(p, length = 4, channels = 1, rampFrames = 8)
        assertEquals(listOf(500, 0, 1000, 1000), samples(p))
    }
}
