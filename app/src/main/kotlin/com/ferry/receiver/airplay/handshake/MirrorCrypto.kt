package com.ferry.receiver.airplay.handshake

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MirrorCrypto — key derivation and helpers for the AirPlay mirroring video stream.
 *
 * The stream is AES-128-CTR encrypted. RPiPlay's per-packet `og`/`nextDecryptCount`
 * bookkeeping (lib/mirror_buffer.c) reduces to a single continuous CTR keystream over the
 * concatenated video payloads (every full-block chunk leaves the cipher block-aligned, so
 * `aes_ctr_start_fresh_block` is always a no-op) — so one [Cipher] with sequential
 * `update()` per payload is exactly equivalent.
 *
 * Reference: RPiPlay lib/mirror_buffer.c (mirror_buffer_init_aes / mirror_buffer_decrypt).
 */
object MirrorCrypto {

    /**
     * Builds the AES-128-CTR cipher that decrypts the mirror video stream.
     *
     * key = SHA512("AirPlayStreamKey"+id ‖ eaeskey)[:16],
     * iv  = SHA512("AirPlayStreamIV"+id ‖ eaeskey)[:16],
     * where eaeskey = SHA512(aesKey ‖ ecdhSecret)[:16] and id is the unsigned decimal
     * streamConnectionID.
     */
    fun streamCipher(aesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long): Cipher {
        val eaeskey = sha512(aesKey + ecdhSecret).copyOf(16)
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII) + eaeskey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII) + eaeskey).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    /**
     * Converts AVCC (4-byte big-endian length-prefixed) NAL units — the format of a decrypted
     * mirror video payload — into Annex-B (00 00 00 01 start codes) that MediaCodec expects,
     * **in place**, and returns the number of valid bytes at the front of [data].
     *
     * An AVCC length prefix and an Annex-B start code are both exactly 4 bytes, so the conversion is
     * a pure overwrite of each prefix — no copy, no second buffer. The previous version allocated a
     * ByteArrayOutputStream plus its toByteArray() copy for every frame, which at 60 fps was two
     * large short-lived allocations a frame on a device with very little GC headroom.
     *
     * The return value can be shorter than [data] when the payload ends with a truncated or
     * malformed record: everything up to that point is valid and is kept, matching the previous
     * behaviour of stopping at the first bad length.
     *
     * @return count of usable bytes from index 0, or 0 if nothing parsed.
     */
    fun avccToAnnexBInPlace(data: ByteArray): Int {
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            // A malformed or truncated record ends the frame; keep everything before it.
            if (len <= 0 || i + 4 + len > data.size) return i
            // Overwrite the 4-byte length prefix with the 4-byte start code, leaving the NAL payload
            // that follows exactly where it already is.
            data[i] = 0; data[i + 1] = 0; data[i + 2] = 0; data[i + 3] = 1
            i += 4 + len
        }
        return i
    }

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Audio stream AES key: SHA-512(aesKey ‖ ecdhSecret)[:16] (the IV is the raw SETUP eiv). */
    fun audioKey(aesKey: ByteArray, ecdhSecret: ByteArray): ByteArray =
        sha512(aesKey + ecdhSecret).copyOf(16)

    private fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)
}
