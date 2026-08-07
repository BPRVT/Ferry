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

    /**
     * The reason [MirrorCrypto.avccToAnnexBInPlace] takes a length at all.
     *
     * The reader decrypts into a **pooled** buffer that is sized to the largest frame seen so far, so
     * it is routinely longer than the frame in it and the tail still holds bytes from an *earlier*
     * frame. Walking to `data.size` would find the previous frame's perfectly well-formed length
     * prefix sitting past the end of this one and splice a NAL made of stale video onto a good frame
     * — silently, with no exception and no dropped frame to count.
     *
     * The tail here is a valid record on purpose: a bound that is checked but wrong (or absent) is
     * exactly what this has to catch, and a tail of zeros would pass either way.
     */
    @Test
    fun `bytes past the frame length are never read, even when they parse`() {
        val frame = avcc(byteArrayOf(0x65, 0x0A, 0x0B))
        val stale = avcc(byteArrayOf(0x41, 0x01, 0x02, 0x03, 0x04, 0x05))
        val pooled = frame + stale

        val len = MirrorCrypto.avccToAnnexBInPlace(pooled, frame.size)

        assertEquals("only this frame's bytes are valid", frame.size, len)
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x65, 0x0A, 0x0B), pooled.copyOf(len).toList())
        // The stale record must still be intact — rewriting its prefix would mean it was walked.
        assertEquals(
            "the tail was rewritten, so the walk ran past the frame length",
            listOf<Byte>(0, 0, 0, 6), pooled.copyOfRange(frame.size, frame.size + 4).toList()
        )
    }

    /** A record that straddles the end of the frame is truncated, not completed from stale bytes. */
    @Test
    fun `a record extending past the frame length is refused`() {
        val good = avcc(byteArrayOf(0x65, 0x0A))
        // Claims 8 bytes and the array really does hold 8 more — but only 2 of them are this frame's.
        val straddling = byteArrayOf(0, 0, 0, 8, 0x41, 0x42) + ByteArray(6) { 0x7F }
        val pooled = good + straddling

        assertEquals(good.size, MirrorCrypto.avccToAnnexBInPlace(pooled, good.size + 6))
    }

    @Test
    fun `an out-of-range length is clamped rather than throwing`() {
        val buf = avcc(byteArrayOf(0x65, 0x0A))
        assertEquals(buf.size, MirrorCrypto.avccToAnnexBInPlace(buf, buf.size + 4096))
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(buf, -1))
    }

    @Test
    fun `malformed and empty inputs return zero rather than throwing`() {
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(ByteArray(0)))
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(byteArrayOf(0, 0)))
        // A zero length is not a valid record.
        assertEquals(0, MirrorCrypto.avccToAnnexBInPlace(byteArrayOf(0, 0, 0, 0, 0x65)))
    }
}
