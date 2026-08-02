package com.ferry.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Regression tests for the two remote-input hardening fixes in [RtspRequestReader].
 *
 * Both are reachable from any device on the LAN before any pairing, so they are worth pinning.
 */
class RtspRequestReaderHardeningTest {

    private fun reader() = RtspRequestReader(maxMessageBytes = 65536, maxPhotoBytes = 25 * 1024 * 1024)

    /**
     * Blank lines before a request line used to recurse once per line. Kotlin does not tail-optimise
     * that, so this input overflowed the stack and killed the connection thread. A loop cannot.
     */
    @Test
    fun `a flood of leading blank lines does not overflow the stack`() {
        val hostile = "\n".repeat(200_000).toByteArray(Charsets.US_ASCII)
        // The assertion is simply that this returns rather than throwing StackOverflowError.
        val result = reader().read(ByteArrayInputStream(hostile))
        assertNull("blank-line flood should be rejected, not parsed", result)
    }

    /** A few blank lines are legal between messages and must still be skipped, not rejected. */
    @Test
    fun `a small number of leading blank lines is tolerated`() {
        val input = "\n\n\nOPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val request = reader().read(ByteArrayInputStream(input))
        assertNotNull(request)
        assertEquals("OPTIONS", request!!.method)
        assertEquals("1", request.headers["CSeq"])
    }

    /**
     * A binary body must not be decoded to a String unless something asks for it. The photo path
     * carries up to 25 MB of JPEG, which as UTF-8 is a multi-megabyte run of replacement characters
     * that no handler reads.
     */
    @Test
    fun `binary body is preserved byte-exact and not eagerly decoded`() {
        // 0xFF 0xD8 is a JPEG SOI; 0xFF is not valid standalone UTF-8, so a decode would corrupt it.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xFF.toByte(), 0xFE.toByte())
        val head = "PUT /photo RTSP/1.0\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val request = reader().read(ByteArrayInputStream(head + jpeg))

        assertNotNull(request)
        assertEquals("bodyBytes must be the exact wire bytes", jpeg.toList(), request!!.bodyBytes.toList())
    }

    /** Text bodies still round-trip through [RtspRequest.body] for the SDP/handshake handlers. */
    @Test
    fun `text body is still readable through body`() {
        val sdp = "v=0\r\no=- 0 0 IN IP4 10.0.0.1\r\n"
        val head = "ANNOUNCE rtsp://x/ferry RTSP/1.0\r\nContent-Length: ${sdp.length}\r\n\r\n"
        val request = reader().read(
            ByteArrayInputStream((head + sdp).toByteArray(Charsets.US_ASCII))
        )
        assertNotNull(request)
        assertEquals(sdp, request!!.body)
    }
}
