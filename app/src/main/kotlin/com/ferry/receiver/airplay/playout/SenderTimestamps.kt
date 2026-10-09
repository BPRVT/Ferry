package com.ferry.receiver.airplay.playout

/**
 * Reads the capture times senders stamp on mirrored video and realtime audio.
 *
 * Both are pure functions of a header the network thread already holds, so pulling them out costs
 * nothing and keeps the format knowledge in one testable place.
 */
object SenderTimestamps {

    /**
     * Capture time of a mirrored video frame, in milliseconds on the sender's clock, or null if the
     * header carries none.
     *
     * The 128-byte mirror header holds it at bytes 8..15 as a **little-endian** 64-bit Q32.32 value:
     * whole seconds in the high 32 bits, a binary fraction in the low 32. This is how UxPlay reads it
     * (`byteutils_get_long(packet, 8)`, a host-order load on a little-endian CPU, then
     * `raop_ntp_timestamp_to_nano_seconds`). Only differences between frames matter here, so the
     * epoch is irrelevant.
     */
    fun mirrorFrameMs(header: ByteArray, offset: Int = 8): Long? {
        if (header.size < offset + 8) return null
        var raw = 0L
        for (i in 7 downTo 0) raw = (raw shl 8) or (header[offset + i].toLong() and 0xFF)
        if (raw == 0L) return null
        val seconds = raw ushr 32
        val fraction = raw and 0xFFFF_FFFFL
        return seconds * 1000 + (fraction * 1000 ushr 32)
    }

    /**
     * RTP timestamp of an audio packet (big-endian bytes 4..7 of the RTP header), in samples.
     * Unsigned, so returned as a Long.
     */
    fun rtpTimestamp(packet: ByteArray, offset: Int = 0): Long? {
        if (packet.size < offset + 8) return null
        return ((packet[offset + 4].toLong() and 0xFF) shl 24) or
            ((packet[offset + 5].toLong() and 0xFF) shl 16) or
            ((packet[offset + 6].toLong() and 0xFF) shl 8) or
            (packet[offset + 7].toLong() and 0xFF)
    }
}

/**
 * Unwraps a 32-bit RTP sample clock into a continuous millisecond timeline.
 *
 * The raw timestamp wraps every 2^32 samples — about 27 hours at 44.1 kHz, so rarely, but a TV that
 * stays on will get there — and only its differences are meaningful anyway. Packets can arrive out of
 * order, so each step is taken as the signed 32-bit distance from the previous one.
 */
class RtpClock(private val sampleRate: Int) {
    private var lastRaw = -1L
    private var unwrappedSamples = 0L

    fun toMs(rawTimestamp: Long): Long {
        if (lastRaw < 0) {
            lastRaw = rawTimestamp
        } else {
            val step = ((rawTimestamp - lastRaw) and 0xFFFF_FFFFL).let { if (it >= 0x8000_0000L) it - 0x1_0000_0000L else it }
            unwrappedSamples += step
            lastRaw = rawTimestamp
        }
        return unwrappedSamples * 1000 / sampleRate.coerceAtLeast(1)
    }
}
