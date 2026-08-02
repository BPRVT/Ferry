package com.ferry.receiver.airplay.handshake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MirrorStreamServer.isDisposable] — the frame-drop preference that makes a shallow
 * decode queue safe.
 *
 * WHY THIS MATTERS: the queue was cut from 90 frames (~1.5 s of latency) to 16, which means
 * overflow is now a routine event rather than a rare one. Overflow used to always cost a keyframe
 * resync, and iOS emits IDRs seconds apart, so a shallow queue with the old policy would trade
 * steady lag for repeated multi-second freezes. Dropping only frames that nothing references is
 * what makes the shallow queue affordable — so a wrong answer here is a visibly broken picture.
 *
 * A false positive (calling a reference frame disposable) corrupts the stream silently; a false
 * negative merely falls back to the old behaviour. The tests below lean on that asymmetry.
 */
class MirrorFrameDropTest {

    /**
     * Builds an Annex-B frame from (nal_ref_idc, nal_unit_type) pairs.
     * Header byte layout: forbidden_zero(1) | nal_ref_idc(2) | nal_unit_type(5).
     */
    private fun frame(vararg nals: Pair<Int, Int>): ByteArray {
        val out = ArrayList<Byte>()
        for ((refIdc, type) in nals) {
            out.add(0); out.add(0); out.add(1)
            out.add((((refIdc and 0x3) shl 5) or (type and 0x1F)).toByte())
            out.add(0x42)  // a byte of slice payload, so NALs aren't zero-length
        }
        return out.toByteArray()
    }

    @Test
    fun `non-reference P-slice is disposable`() {
        // nal_ref_idc = 0, type 1 — the case the whole optimisation exists for.
        assertTrue(MirrorStreamServer.isDisposable(frame(0 to 1)))
    }

    @Test
    fun `reference P-slice is not disposable`() {
        // Any non-zero nal_ref_idc means a later frame may predict from this one.
        assertFalse(MirrorStreamServer.isDisposable(frame(1 to 1)))
        assertFalse(MirrorStreamServer.isDisposable(frame(2 to 1)))
        assertFalse(MirrorStreamServer.isDisposable(frame(3 to 1)))
    }

    @Test
    fun `IDR is never disposable`() {
        // An IDR carries nal_ref_idc != 0 in any sane stream, and dropping one strands every
        // frame that follows it.
        assertFalse(MirrorStreamServer.isDisposable(frame(3 to 5)))
    }

    @Test
    fun `frame is disposable only when every slice is non-reference`() {
        // Multi-slice frame: one referenced slice is enough to make the whole frame load-bearing.
        assertTrue(MirrorStreamServer.isDisposable(frame(0 to 1, 0 to 1, 0 to 1)))
        assertFalse(MirrorStreamServer.isDisposable(frame(0 to 1, 2 to 1, 0 to 1)))
        assertFalse(MirrorStreamServer.isDisposable(frame(0 to 1, 0 to 1, 1 to 1)))
    }

    @Test
    fun `non-slice NALs do not make a frame disposable`() {
        // SPS(7)/PPS(8)/SEI(6) carry no picture data. A payload of only these has no slice to
        // drop, and treating it as disposable would throw away configuration.
        assertFalse(MirrorStreamServer.isDisposable(frame(3 to 7, 3 to 8)))
        assertFalse(MirrorStreamServer.isDisposable(frame(0 to 6)))
    }

    @Test
    fun `non-reference slice alongside parameter sets is still disposable`() {
        // SEI ahead of a non-reference slice must not mask the slice's verdict.
        assertTrue(MirrorStreamServer.isDisposable(frame(0 to 6, 0 to 1)))
    }

    @Test
    fun `degenerate input is never disposable`() {
        // Conservative default: no slices seen → not safe to drop.
        assertFalse(MirrorStreamServer.isDisposable(ByteArray(0)))
        assertFalse(MirrorStreamServer.isDisposable(byteArrayOf(0, 0, 1)))       // truncated header
        assertFalse(MirrorStreamServer.isDisposable(byteArrayOf(1, 2, 3, 4)))    // no start code
    }

    @Test
    fun `scan is not derailed by payload bytes that look like a start code prefix`() {
        // Real slice payloads contain 00 00 sequences. The first NAL is a reference slice, so the
        // frame must come back non-disposable no matter what the trailing bytes resemble.
        val tricky = byteArrayOf(
            0, 0, 1, ((1 shl 5) or 1).toByte(),   // reference P-slice
            0x00, 0x00, 0x03, 0x01,               // emulation-prevention-ish payload
        )
        assertFalse(MirrorStreamServer.isDisposable(tricky))
    }
}
