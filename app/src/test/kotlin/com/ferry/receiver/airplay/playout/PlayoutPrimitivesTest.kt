package com.ferry.receiver.airplay.playout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayoutPrimitivesTest {

    @Test
    fun `windowed max forgets values older than the window`() {
        val w = WindowedExtreme(windowMs = 2_000, bucketMs = 500, keepMax = true)
        w.add(0, 50)
        w.add(600, 10)
        assertEquals(50L, w.get(1_000))
        assertEquals(10L, w.get(2_200))
        assertNull(w.get(10_000))
    }

    @Test
    fun `windowed min keeps the smallest`() {
        val w = WindowedExtreme(windowMs = 2_000, bucketMs = 500, keepMax = false)
        w.add(0, 50); w.add(100, 20); w.add(700, 30)
        assertEquals(20L, w.get(800))
        w.clear()
        assertNull(w.get(800))
    }

    @Test
    fun `mirror header timestamp is little-endian Q32_32 seconds`() {
        // 1000.5 seconds: high word 1000, low word 0x80000000. Little-endian at bytes 8..15.
        val raw = (1000L shl 32) or 0x8000_0000L
        val header = ByteArray(128)
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()
        assertEquals(1_000_500L, SenderTimestamps.mirrorFrameMs(header))
    }

    @Test
    fun `an all-zero mirror timestamp means none`() {
        assertNull(SenderTimestamps.mirrorFrameMs(ByteArray(128)))
        assertNull(SenderTimestamps.mirrorFrameMs(ByteArray(10)))
    }

    @Test
    fun `rtp timestamp is read big-endian and unsigned`() {
        val pkt = byteArrayOf(0x80.toByte(), 0x60, 0, 1, 0xFF.toByte(), 0, 0, 0x10)
        assertEquals(0xFF000010L, SenderTimestamps.rtpTimestamp(pkt))
    }

    @Test
    fun `rtp clock survives the 32-bit wrap`() {
        val c = RtpClock(44_100)
        assertEquals(0L, c.toMs(0xFFFF_FE00L))
        // 0x200 + 0x1000 samples later, across the wrap.
        assertEquals((0x1200L * 1000) / 44_100, c.toMs(0x1000L))
    }

    @Test
    fun `rtp clock tolerates a packet arriving out of order`() {
        val c = RtpClock(48_000)
        c.toMs(48_000)
        assertEquals(1_000L, c.toMs(96_000))
        assertEquals(500L, c.toMs(72_000))
    }
}
