package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.classify], the single pass that decides both whether a frame is
 * disposable and whether it is a keyframe.
 *
 * The drop-policy behaviour itself is covered by MirrorFrameDropTest; these focus on keyframe
 * detection and on the early exit, which is the part with a real correctness argument behind it.
 */
class MirrorFrameClassifyTest {

    /** Builds an Annex-B frame from (nal_ref_idc, nal_unit_type) pairs. */
    private fun frame(vararg nals: Pair<Int, Int>): ByteArray {
        val out = ArrayList<Byte>()
        for ((refIdc, type) in nals) {
            out.addAll(listOf<Byte>(0, 0, 0, 1))
            out.add((((refIdc and 0x03) shl 5) or (type and 0x1F)).toByte())
            out.addAll(listOf<Byte>(0x11, 0x22, 0x33))   // a little payload
        }
        return out.toByteArray()
    }

    @Test
    fun `an IDR slice is a keyframe`() {
        // IDR slices always carry nal_ref_idc != 0 per the spec.
        assertTrue(MirrorStreamServer.isKeyframe(frame(3 to 5)))
    }

    @Test
    fun `a non-IDR frame is not a keyframe`() {
        assertFalse(MirrorStreamServer.isKeyframe(frame(2 to 1)))
        assertFalse(MirrorStreamServer.isKeyframe(frame(0 to 1)))
    }

    @Test
    fun `an IDR frame is a keyframe and not disposable`() {
        val flags = MirrorStreamServer.classify(frame(3 to 5))
        assertEquals(
            MirrorStreamServer.FLAG_KEYFRAME,
            flags and (MirrorStreamServer.FLAG_KEYFRAME or MirrorStreamServer.FLAG_DISPOSABLE)
        )
    }

    /**
     * The early exit fires on the first referenced slice. Keyframe-ness must already be settled at
     * that point — H.264 does not mix IDR and non-IDR slices in one access unit, and an IDR slice is
     * always referenced, so the IDR flag is set on the very NAL that triggers the break.
     */
    @Test
    fun `keyframe is still detected when the early exit fires on the first slice`() {
        // A single referenced IDR slice followed by more data: the break happens immediately.
        val f = frame(3 to 5, 3 to 5, 3 to 5)
        assertTrue(MirrorStreamServer.isKeyframe(f))
        assertFalse(MirrorStreamServer.isDisposable(f))
    }

    @Test
    fun `a non-reference frame is disposable and not a keyframe`() {
        val flags = MirrorStreamServer.classify(frame(0 to 1, 0 to 1))
        assertEquals(
            MirrorStreamServer.FLAG_DISPOSABLE,
            flags and (MirrorStreamServer.FLAG_KEYFRAME or MirrorStreamServer.FLAG_DISPOSABLE)
        )
    }

    @Test
    fun `a frame with no slice NALs is neither`() {
        // SPS (7) + PPS (8) only — no slices.
        assertEquals(0, MirrorStreamServer.classify(frame(3 to 7, 3 to 8)))
    }

    /** Only the first [length] bytes are considered, matching the in-place conversion contract. */
    @Test
    fun `bytes past the reported length are ignored`() {
        val real = frame(0 to 1)                 // disposable, not a keyframe
        val tail = frame(3 to 5)                 // an IDR that must NOT be seen
        val buf = real + tail

        assertFalse("tail must not turn this into a keyframe",
            MirrorStreamServer.isKeyframe(buf, real.size))
        assertTrue("tail must not affect disposability",
            MirrorStreamServer.isDisposable(buf, real.size))
    }
}
