package com.ferry.receiver.util

/**
 * Short linear fades on 16-bit little-endian interleaved PCM, applied in place.
 *
 * Exists for the edges of a gap. When the Wi-Fi stalls long enough that the audio buffer runs dry,
 * the speaker goes from a waveform straight to silence and later from silence straight back into a
 * waveform. Both edges are steps, and a step is a click. A few milliseconds of ramp on each side
 * turns the click into a soft dip, which on speech and music is barely noticeable.
 *
 * Pure, so it is tested off-device.
 */
object PcmFade {

    /** Ramps the first [rampFrames] frames of [pcm] up from silence. */
    fun fadeIn(pcm: ByteArray, length: Int, channels: Int, rampFrames: Int) =
        ramp(pcm, length, channels, rampFrames, fadeIn = true)

    /** Ramps the last [rampFrames] frames of [pcm] down to silence. */
    fun fadeOut(pcm: ByteArray, length: Int, channels: Int, rampFrames: Int) =
        ramp(pcm, length, channels, rampFrames, fadeIn = false)

    private fun ramp(pcm: ByteArray, length: Int, channels: Int, rampFrames: Int, fadeIn: Boolean) {
        val ch = channels.coerceAtLeast(1)
        val frameBytes = 2 * ch
        val totalFrames = length.coerceAtMost(pcm.size) / frameBytes
        val n = rampFrames.coerceAtMost(totalFrames)
        if (n <= 0) return
        val firstFrame = if (fadeIn) 0 else totalFrames - n
        for (k in 0 until n) {
            // fadeIn: 0/n .. (n-1)/n ; fadeOut: (n-1)/n .. 0/n — so the outermost sample is silent.
            val num = if (fadeIn) k else n - 1 - k
            val frame = firstFrame + k
            for (c in 0 until ch) {
                val i = frame * frameBytes + c * 2
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                val scaled = sample * num / n
                pcm[i] = scaled.toByte()
                pcm[i + 1] = (scaled shr 8).toByte()
            }
        }
    }

    /** Frames in [ms] milliseconds at [sampleRate]. */
    fun framesFor(ms: Int, sampleRate: Int): Int = (sampleRate.toLong() * ms / 1000).toInt()
}
