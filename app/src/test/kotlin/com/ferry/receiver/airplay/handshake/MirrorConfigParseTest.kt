package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The reader thread reuses one payload buffer, so a config frame is almost always sitting in an
 * array larger than itself. These tests pin the consequence: every length in the payload is bounded
 * by the frame's real size, not by the array's — otherwise a corrupt or hostile length field reads
 * whatever the previous frame left behind, stays in bounds, throws nothing, and the decoder gets
 * configured from stale bytes.
 */
class MirrorConfigParseTest {

    /** Builds a well-formed type-1 config payload: 6 bytes of preamble, SPS, PPS count, PPS. */
    private fun config(sps: ByteArray, pps: ByteArray): ByteArray =
        ByteArray(6) +
            byteArrayOf((sps.size shr 8).toByte(), sps.size.toByte()) + sps +
            byteArrayOf(1) +
            byteArrayOf((pps.size shr 8).toByte(), pps.size.toByte()) + pps

    @Test
    fun `parses sps and pps from an exactly-sized payload`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val payload = config(sps, pps)

        val parsed = MirrorStreamServer.parseSpsPps(payload, payload.size)

        assertNotNull(parsed)
        assertArrayEquals(sps, parsed!!.first)
        assertArrayEquals(pps, parsed.second)
    }

    @Test
    fun `parses correctly when the buffer is longer than the frame`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val exact = config(sps, pps)
        // The reused-buffer case: same frame, sitting in a much larger array.
        val oversized = exact.copyOf(exact.size + 4096)

        val parsed = MirrorStreamServer.parseSpsPps(oversized, exact.size)

        assertNotNull(parsed)
        assertArrayEquals(sps, parsed!!.first)
        assertArrayEquals(pps, parsed.second)
    }

    @Test
    fun `rejects an sps length that runs past the frame but fits the buffer`() {
        // The regression this guards: spsSize points beyond the real frame into stale bytes that
        // are still inside the array, so bounds-checking the array alone would let it through.
        val payload = ByteArray(4096)
        payload[6] = 0x01; payload[7] = 0x00        // spsSize = 256, but the frame is only 32 bytes

        assertNull(MirrorStreamServer.parseSpsPps(payload, 32))
    }

    @Test
    fun `rejects a pps length that runs past the frame but fits the buffer`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val frame = config(sps, byteArrayOf(0x68, 0x11))
        val buffer = frame.copyOf(4096)
        // Overwrite the PPS length with something that overruns the frame but not the array.
        val ppsLenOffset = 8 + sps.size + 1
        buffer[ppsLenOffset] = 0x01; buffer[ppsLenOffset + 1] = 0x00   // ppsSize = 256

        assertNull(MirrorStreamServer.parseSpsPps(buffer, frame.size))
    }

    @Test
    fun `rejects truncated payloads and zero lengths`() {
        assertNull(MirrorStreamServer.parseSpsPps(ByteArray(4), 4))          // shorter than the header
        assertNull(MirrorStreamServer.parseSpsPps(ByteArray(64), 0))         // empty frame
        assertNull(MirrorStreamServer.parseSpsPps(ByteArray(64), 32))        // spsSize = 0
    }
}
