package com.ferry.receiver.airplay.playout

import kotlin.random.Random

/**
 * The bad-Wi-Fi simulator: synthetic arrival traces with the failure shapes real home Wi-Fi has,
 * and a model of what then reaches the screen.
 *
 * Mirrored video rides TCP, so the network never *loses* a frame — it delays it. That makes the
 * whole problem a question of timing, which is exactly what can be simulated faithfully without a
 * TV, an iPad or a radio:
 *
 *  - **jitter**: each frame is a little late by a random amount;
 *  - **stalls**: the link delivers nothing for a while (interference, a roaming hop, a microwave);
 *  - **bursts**: when a stall ends, everything held up arrives back to back at link speed, in order.
 *
 * [replay] then plays a trace through a [PlayoutScheduler] (or straight through, the pre-8.1
 * behaviour) and reports what a viewer would see. Real traces captured on the TV via the diagnostics
 * page (`/trace.csv`, see [NetworkTrace]) replay through the same function.
 */
object BadWifi {

    data class Stall(val startMs: Long, val lengthMs: Long)

    /**
     * Frames captured at [fps] for [durationMs], each delivered [baseDelayMs] + up to [jitterMs]
     * after capture, held up by [stalls], and kept in order the way TCP keeps them.
     *
     * [idle] ranges are a static screen: the sender captures nothing, which is normal and must never
     * be mistaken for a network problem.
     */
    fun trace(
        durationMs: Long,
        fps: Int = 60,
        baseDelayMs: Long = 8,
        jitterMs: Long = 0,
        stalls: List<Stall> = emptyList(),
        idle: List<LongRange> = emptyList(),
        burstSpacingMs: Long = 1,
        seed: Int = 1,
        senderClockOffsetMs: Long = 3_600_000,
    ): List<NetworkTrace.Arrival> {
        val rng = Random(seed)
        val out = ArrayList<NetworkTrace.Arrival>()
        val interval = 1000.0 / fps
        var lastArrival = Long.MIN_VALUE
        var n = 0
        while (true) {
            val capture = (n * interval).toLong()
            n++
            if (capture >= durationMs) break
            if (idle.any { capture in it }) continue
            var arrival = capture + baseDelayMs + if (jitterMs > 0) rng.nextLong(jitterMs + 1) else 0
            for (s in stalls) {
                if (arrival >= s.startMs && arrival < s.startMs + s.lengthMs) arrival = s.startMs + s.lengthMs
            }
            if (lastArrival != Long.MIN_VALUE) arrival = maxOf(arrival, lastArrival + burstSpacingMs)
            lastArrival = arrival
            out += NetworkTrace.Arrival(capture + senderClockOffsetMs, arrival, 20_000, keyframe = n == 1)
        }
        return out
    }

    /** What a viewer sees when a trace is played. */
    data class Result(
        /** Times the picture sat still for longer than [FREEZE_MS] while the sender was moving. */
        val freezes: Int,
        /** Longest such freeze. */
        val longestFreezeMs: Long,
        /** Frames shown less than [RUSH_MS] after the previous one: the "fast-forward" after a stall. */
        val rushedFrames: Int,
        /** Delay the scheduler had settled on at the end, or 0 straight through. */
        val finalDelayMs: Long,
        /** The largest delay it used at any point. */
        val peakDelayMs: Long,
        /** How far behind the sender the picture ran, worst case. */
        val worstLagMs: Long,
    )

    /**
     * Plays [arrivals] in order, each at `max(its scheduled time, its arrival, the previous frame's
     * display time)` — a FIFO, like the real decode queue — and measures the result.
     *
     * A gap counts as a freeze only when the sender's own frames were close together across it. A
     * gap the sender made (a static screen) is not something the receiver should or could hide.
     */
    fun replay(arrivals: List<NetworkTrace.Arrival>, scheduler: PlayoutScheduler?): Result {
        var prevDisplay = Long.MIN_VALUE
        var prevSender = Long.MIN_VALUE
        var freezes = 0
        var longest = 0L
        var rushed = 0
        var peak = 0L
        var worstLag = 0L
        var firstOffset = Long.MIN_VALUE
        for (a in arrivals) {
            val scheduled = scheduler?.schedule(a.senderMs, a.arrivalMs) ?: a.arrivalMs
            peak = maxOf(peak, scheduler?.targetDelayMs ?: 0)
            var display = maxOf(scheduled, a.arrivalMs)
            if (prevDisplay != Long.MIN_VALUE) display = maxOf(display, prevDisplay)
            if (firstOffset == Long.MIN_VALUE) firstOffset = a.arrivalMs - a.senderMs
            worstLag = maxOf(worstLag, display - (a.senderMs + firstOffset))
            if (prevDisplay != Long.MIN_VALUE) {
                val gap = display - prevDisplay
                val senderGap = a.senderMs - prevSender
                if (senderGap < SENDER_CONTINUOUS_MS && gap > FREEZE_MS) {
                    freezes++
                    longest = maxOf(longest, gap)
                }
                if (gap < RUSH_MS && senderGap >= RUSH_MS) rushed++
            }
            prevDisplay = display
            prevSender = a.senderMs
        }
        return Result(freezes, longest, rushed, scheduler?.targetDelayMs ?: 0, peak, worstLag)
    }

    const val FREEZE_MS = 100L
    const val RUSH_MS = 5L
    private const val SENDER_CONTINUOUS_MS = 50L
}
