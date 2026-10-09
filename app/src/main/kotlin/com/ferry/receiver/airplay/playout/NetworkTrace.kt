package com.ferry.receiver.airplay.playout

/**
 * A rolling record of exactly when each video frame arrived, for replaying real bad-Wi-Fi sessions
 * in the bad-Wi-Fi simulator (`PlayoutSimulatorTest`).
 *
 * Every buffering decision Ferry makes depends on the *timing* of arrivals, and that timing is the
 * one thing a log line cannot capture well. This keeps the last [CAPACITY] arrivals — about four
 * minutes at 60 fps — as `(sender capture ms, local arrival ms, bytes, keyframe)`, and the
 * diagnostics page serves them as CSV at `/trace.csv`. A trace captured on the TV during a bad
 * moment replays deterministically in a unit test, so a buffering change can be judged against the
 * Wi-Fi that actually caused the problem instead of a guess.
 *
 * Fixed-size primitive arrays, written by the video reader thread only; [csv] copies under the lock.
 * A few hundred kilobytes, held for the life of the process.
 */
object NetworkTrace {
    const val CAPACITY = 16_384

    private val senderMs = LongArray(CAPACITY)
    private val arrivalMs = LongArray(CAPACITY)
    private val bytes = IntArray(CAPACITY)
    private val keyframe = BooleanArray(CAPACITY)
    private var next = 0
    private var count = 0

    @Synchronized
    fun record(sender: Long, arrival: Long, size: Int, isKeyframe: Boolean) {
        senderMs[next] = sender
        arrivalMs[next] = arrival
        bytes[next] = size
        keyframe[next] = isKeyframe
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    @Synchronized
    fun clear() {
        next = 0
        count = 0
    }

    /** Oldest first: `sender_ms,arrival_ms,bytes,keyframe`, with a header line. */
    @Synchronized
    fun csv(): String {
        val out = StringBuilder(count * 32 + 64)
        out.append(HEADER).append('\n')
        val start = (next - count + CAPACITY) % CAPACITY
        for (n in 0 until count) {
            val i = (start + n) % CAPACITY
            out.append(senderMs[i]).append(',').append(arrivalMs[i]).append(',')
                .append(bytes[i]).append(',').append(if (keyframe[i]) 1 else 0).append('\n')
        }
        return out.toString()
    }

    const val HEADER = "sender_ms,arrival_ms,bytes,keyframe"

    /** One arrival, as parsed back out of [csv]. */
    data class Arrival(val senderMs: Long, val arrivalMs: Long, val bytes: Int, val keyframe: Boolean)

    /** Parses [csv] output (header optional, blank lines ignored). */
    fun parse(text: String): List<Arrival> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("sender_ms") }
        .map { line ->
            val f = line.split(',')
            Arrival(f[0].toLong(), f[1].toLong(), f.getOrNull(2)?.toInt() ?: 0, f.getOrNull(3) == "1")
        }
        .toList()
}
