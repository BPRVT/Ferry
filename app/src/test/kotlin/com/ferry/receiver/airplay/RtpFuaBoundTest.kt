package com.ferry.receiver.airplay

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Regression test for the FU-A reassembly bound.
 *
 * Nothing in the FU-A format obliges a sender to ever set the E (end) bit, so a peer could send one
 * start fragment and then middle fragments indefinitely. The accumulator grew unbounded until the
 * heap died — reachable from any LAN device against an unpaired receiver.
 */
class RtpFuaBoundTest {

    /**
     * Feeds far more FU-A fragment data than [RtpInterleaved]'s cap allows, never setting the end
     * bit. The reassembly must be abandoned rather than accumulated, and no NAL unit delivered.
     */
    @Test
    fun `endless FU-A fragments without an end bit are discarded, not accumulated`() {
        val nri = 0x60
        val nalType = 0x05
        val chunk = ByteArray(1400) { 0x41 }        // ~1.4 KB of fragment payload per packet

        val stream = java.io.ByteArrayOutputStream()
        // Start fragment, then ~5 MB of middle fragments — past the 4 MB cap, no end bit ever.
        stream.write(interleaved(fua(nri, isStart = true, isEnd = false, nalType, chunk)))
        repeat(3600) {
            stream.write(interleaved(fua(nri, isStart = false, isEnd = false, nalType, chunk)))
        }

        var received: ByteArray? = null
        RtpInterleaved.readLoop(
            ByteArrayInputStream(stream.toByteArray()),
            { nal, _ -> received = nal },
            {}
        )

        assertNull("an unterminated FU-A must never be delivered as a NAL unit", received)
    }

    /** The cap must not break a legitimate large keyframe that does terminate. */
    @Test
    fun `a large but terminated FU-A still reassembles`() {
        val nri = 0x60
        val nalType = 0x05
        val chunk = ByteArray(1400) { 0x42 }

        val stream = java.io.ByteArrayOutputStream()
        stream.write(interleaved(fua(nri, isStart = true, isEnd = false, nalType, chunk)))
        repeat(50) {
            stream.write(interleaved(fua(nri, isStart = false, isEnd = false, nalType, chunk)))
        }
        stream.write(interleaved(fua(nri, isStart = false, isEnd = true, nalType, chunk)))

        var received: ByteArray? = null
        RtpInterleaved.readLoop(
            ByteArrayInputStream(stream.toByteArray()),
            { nal, _ -> received = nal },
            {}
        )

        assertNotNull("a terminated FU-A under the cap must still be delivered", received)
    }

    private fun fua(nri: Int, isStart: Boolean, isEnd: Boolean, nalType: Int, data: ByteArray): ByteArray {
        val fuIndicator = (nri or 28).toByte()
        val seBits = (if (isStart) 0x80 else 0) or (if (isEnd) 0x40 else 0)
        val fuHeader = (seBits or (nalType and 0x1F)).toByte()
        return byteArrayOf(fuIndicator, fuHeader) + data
    }

    private fun interleaved(payload: ByteArray): ByteArray {
        val rtp = ByteArray(12 + payload.size)
        rtp[0] = 0x80.toByte()
        rtp[1] = 0x60.toByte()
        rtp[2] = 0x00; rtp[3] = 0x01
        payload.copyInto(rtp, destinationOffset = 12)
        val len = rtp.size
        return byteArrayOf(0x24, 0x00, (len shr 8).toByte(), (len and 0xFF).toByte()) + rtp
    }
}
