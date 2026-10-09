package com.ferry.receiver.airplay.playout

/**
 * The minimum or maximum of a value over a sliding time window, in constant memory.
 *
 * Samples are folded into fixed-width buckets, so the answer is exact to within one bucket of the
 * window's edge and costs `window / bucket` longs no matter how many samples arrive. That matters:
 * this sees every video frame (60 a second) and every audio packet (~90 a second) for as long as a
 * session lives.
 *
 * Pure and Android-free so the playout policy built on it can be tested without a device.
 */
internal class WindowedExtreme(
    private val windowMs: Long,
    private val bucketMs: Long,
    private val keepMax: Boolean,
) {
    private val size = (windowMs / bucketMs).toInt().coerceAtLeast(1)
    private val starts = LongArray(size) { EMPTY }
    private val values = LongArray(size)

    fun add(nowMs: Long, value: Long) {
        val slotStart = nowMs - Math.floorMod(nowMs, bucketMs)
        val i = Math.floorMod(slotStart / bucketMs, size.toLong()).toInt()
        if (starts[i] != slotStart) {
            starts[i] = slotStart
            values[i] = value
        } else {
            values[i] = if (keepMax) maxOf(values[i], value) else minOf(values[i], value)
        }
    }

    /** The extreme over the last [windowMs], or null if nothing landed inside it. */
    fun get(nowMs: Long): Long? {
        var found = false
        var best = 0L
        for (i in 0 until size) {
            val start = starts[i]
            if (start == EMPTY || nowMs - start >= windowMs || start > nowMs) continue
            val v = values[i]
            if (!found || (keepMax && v > best) || (!keepMax && v < best)) best = v
            found = true
        }
        return if (found) best else null
    }

    fun clear() {
        starts.fill(EMPTY)
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}
