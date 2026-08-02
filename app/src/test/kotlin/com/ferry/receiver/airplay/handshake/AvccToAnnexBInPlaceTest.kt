package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the in-place AVCC → Annex-B rewrite.
 *
 * An AVCC 4-byte big-endian length prefix and an Annex-B 4-byte start code are the same width, so
 * the conversion is a pure overwrite. These pin both the output bytes and the "how much is valid"
 * contract, since the buffer is deliberately allowed to have a tail beyond the returned length.
 */
class AvccToAnnexBInPlaceTest {

    private fun avcc(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (n in nals) {
            out.add((n.size ushr 24).toByte()); out.add((n.size ushr 16).toByte())
            out.add((n.size ushr 8).toByte()); out.add(n.size.toByte())
            out.addAll(n.toList())
        }
        return out.toByteArray()
    }

    @Test
    fun `single NAL gets its length prefix replaced by a start code`() {
        val nal = byteArrayOf(0x65, 0x11, 0x22, 0x33)
        val buf = avcc(nal)

        val len = MirrorCrypto.avccToAnnexBInPlace(buf)

        assertEquals(8, len)
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33), buf.copyOf(len).toList())
    }

    @Test
    fun `multiple NALs are all converted and the payloads are untouched`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00)
        val idr = byteArrayOf(0x65, 0x01, 0x02, 0x03, 0x04)
        val buf = avcc(sps, idr)

        val len = MirrorCrypto.avccToAnnexBInPlace(buf)

        assertEquals(buf.size, len)
        assertEquals(
            listOf<Byte>(0, 0, 0, 1, 0x67, 0x42, 0x00, 0, 0, 0, 1, 0x65, 0x01, 0x02, 0x03, 0x04),
            buf.copyOf(len).toList()
        )
    }

    /**
     * A truncated trailing record keeps everything before it, matching the old implementation's
     * "stop at the first bad length" behaviour rather than discarding the whole frame.
     */
    @Test
    fun `a truncated trailing record keeps the valid prefix`() {
        val good = byteArrayOf(0x65, 0x0A)
        // Claims 99 bytes but supplies 2.
        val truncated = byteArrayOf(0, 0, 0, 99, 0x41, 0x42)
        val buf = avcc(good) + truncated

        val len = MirrorCrypto.avccToAnnexBInPlace(buf)

        assertEquals("only the first complete NAL is valid", 6, len)
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x65, 0x0A), buf.copyOf(len).toList())
    }

    @Test
    fun `malformed and empty inputs return zero rather than throwing`() {
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(ByteArray(0)))
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(byteArrayOf(0, 0)))
        // A zero length is not a valid record.
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(byteArrayOf(0, 0, 0, 0, 0x65)))
    }
}
