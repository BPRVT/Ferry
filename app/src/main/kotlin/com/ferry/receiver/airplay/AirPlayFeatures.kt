package com.ferry.receiver.airplay

import java.util.Locale

/**
 * AirPlayFeatures — single source of truth for the AirPlay `features` capability bitmask.
 *
 * A sender reads this value three separate times before any media flows: from the
 * `_airplay._tcp` mDNS TXT record during discovery ([MdnsService]), from `GET /info`
 * ([com.ferry.receiver.airplay.handshake.InfoResponder]), and from `GET /server-info`
 * ([RtspHandler]). They have to agree — a sender that discovers one capability set and is then
 * told a different one mid-handshake is looking at an incoherent device, and the failure shows up
 * far from its cause. The value used to be three hand-maintained copies of the same literal in
 * three files, so it lives here instead.
 *
 * See `docs/spec/TECHNICAL_SPEC.md` §8 for the full bit-level breakdown.
 */
object AirPlayFeatures {

    /**
     * Bit 0 — `Video`: the AirPlay *video URL* mode, where the sender hands over a media URL and
     * the receiver fetches and plays the stream itself, rather than mirroring the sender's screen.
     * Handled by the `POST /play`, `/rate`, `/scrub`, `/stop` endpoints in [RtspHandler].
     *
     * This is the bit that decides which of the two AirPlay modes a sender offers, and it is the
     * only one [MIRROR_ONLY] clears.
     */
    private const val BIT_VIDEO = 1L shl 0

    /** Everything Ferry implements: screen mirroring, video URL playback, photo, and audio. */
    const val FULL = 0x1E5A7FFFF7L

    /**
     * [FULL] with `Video` cleared, so senders never offer the video-URL route and every session
     * arrives as screen mirroring. Backs the "Force screen mirroring" setting.
     *
     * Withholding the capability is the only reliable lever: by the time a sender sends
     * `POST /play` it has already committed to video mode, so rejecting that request yields a
     * failed playback rather than a fallback to mirroring.
     */
    const val MIRROR_ONLY = FULL and BIT_VIDEO.inv()

    /** The bitmask to advertise, as used by `GET /info` and `GET /server-info`. */
    fun value(forceScreenMirroring: Boolean): Long =
        if (forceScreenMirroring) MIRROR_ONLY else FULL

    /**
     * The bitmask in mDNS TXT form — `"0x<low32>,0x<high32>"`, e.g. `"0x5A7FFFF7,0x1E"`.
     *
     * The 64-bit value is split into two 32-bit halves because that is how AirPlay receivers
     * have always published it; senders parse both halves and recombine them.
     *
     * [Locale.ROOT] because this is wire format: a locale with non-ASCII digits would otherwise
     * produce a TXT record no sender can parse.
     */
    fun txtRecord(forceScreenMirroring: Boolean): String {
        val bits = value(forceScreenMirroring)
        val low = bits and 0xFFFFFFFFL
        val high = (bits ushr 32) and 0xFFFFFFFFL
        return String.format(Locale.ROOT, "0x%X,0x%X", low, high)
    }
}
