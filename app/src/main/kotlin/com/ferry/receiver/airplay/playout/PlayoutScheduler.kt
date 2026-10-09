package com.ferry.receiver.airplay.playout

import kotlin.math.abs

/**
 * Decides when each piece of a live stream should be played, so that Wi-Fi hiccups are absorbed
 * instead of shown.
 *
 * ── The problem ──
 *
 * Mirrored video travels over TCP. On bad Wi-Fi nothing is lost; it is *late*. A stall delivers
 * nothing for a while and then everything at once. Playing each frame the moment it arrives, which
 * is what Ferry did through 8.0.0, turns every stall into a frozen picture followed by a fast-forward,
 * and the burst overflows the decode queue and destroys frames the rest of the stream depends on.
 *
 * ── The fix: play by the sender's clock, a little behind ──
 *
 * Every frame and audio packet carries the time it was captured on the sender. Playing frame `n` at
 *
 *     captureTime(n) + baseTransit + targetDelay
 *
 * reproduces the sender's own cadence, `targetDelay` behind. `baseTransit` is the smallest observed
 * gap between capture and arrival — the network's best case plus the offset between the two clocks,
 * which never needs to be known separately. A frame that arrives up to `targetDelay` late still plays
 * on time, so a stall shorter than that is invisible.
 *
 * ── Adaptive ──
 *
 * `targetDelay` follows how late frames have actually been recently: the worst lateness over
 * [jitterWindowMs], plus [marginMs]. It grows immediately when the link gets worse, and shrinks
 * slowly — [shrinkMsPerSecond] — once it calms, so a good link costs barely any delay and a bad one
 * gets as much cushion as it needs, up to [maxDelayMs]. Shrinking slowly matters: dropping the delay
 * all at once would release a backlog in a burst, which is the thing this exists to prevent.
 *
 * Both windows slide, so slow drift between the sender's clock and this one is tracked rather than
 * accumulated. A jump larger than [resetThresholdMs] (a long dropout, a sender clock reset, a new
 * stream) starts the estimate over instead of treating it as lateness.
 *
 * Pure and clock-agnostic: callers pass both times in milliseconds on clocks of their choosing (the
 * sender's capture clock and a local monotonic clock). That is what lets the bad-Wi-Fi simulator in
 * the tests drive it with synthetic traces.
 */
class PlayoutScheduler(
    val minDelayMs: Long,
    val maxDelayMs: Long,
    private val marginMs: Long = DEFAULT_MARGIN_MS,
    private val jitterWindowMs: Long = DEFAULT_JITTER_WINDOW_MS,
    private val baseWindowMs: Long = DEFAULT_BASE_WINDOW_MS,
    private val shrinkMsPerSecond: Long = DEFAULT_SHRINK_MS_PER_SECOND,
    private val resetThresholdMs: Long = DEFAULT_RESET_THRESHOLD_MS,
) {
    init {
        require(minDelayMs in 0..maxDelayMs) { "need 0 <= minDelayMs <= maxDelayMs" }
    }

    private val baseTransit = WindowedExtreme(baseWindowMs, BUCKET_MS, keepMax = false)
    private val lateness = WindowedExtreme(jitterWindowMs, BUCKET_MS, keepMax = true)
    /**
     * When the delay last shrank, or last stopped being allowed to. Shrinking is measured from here
     * rather than from the previous arrival: frames are ~16 ms apart, and 16 ms at 40 ms/s rounds to
     * nothing, so a per-arrival step would never shrink at all.
     */
    private var shrinkAnchorMs = Long.MIN_VALUE

    /** The delay currently being added on top of the network's best case. */
    @Volatile var targetDelayMs: Long = minDelayMs
        private set

    /** How late the most recent arrival was relative to the best case. Diagnostic only. */
    @Volatile var lastLatenessMs: Long = 0L
        private set

    /** How many times the estimate has been thrown away and restarted. Diagnostic only. */
    var resets: Int = 0
        private set

    /**
     * Records one arrival and returns the local time, on the same clock as [arrivalMs], at which it
     * should be played. A result earlier than [arrivalMs] means "play it now".
     */
    fun schedule(senderMs: Long, arrivalMs: Long): Long {
        val transit = arrivalMs - senderMs
        val previousBase = baseTransit.get(arrivalMs)
        if (previousBase != null && abs(transit - previousBase) > resetThresholdMs) {
            reset()
        }
        baseTransit.add(arrivalMs, transit)
        val base = baseTransit.get(arrivalMs) ?: transit

        val late = (transit - base).coerceAtLeast(0)
        lastLatenessMs = late
        lateness.add(arrivalMs, late)

        val peak = lateness.get(arrivalMs) ?: late
        val desired = (peak + marginMs).coerceIn(minDelayMs, maxDelayMs)
        if (desired >= targetDelayMs || shrinkAnchorMs == Long.MIN_VALUE) {
            targetDelayMs = desired
            shrinkAnchorMs = arrivalMs
        } else {
            val step = (arrivalMs - shrinkAnchorMs).coerceAtLeast(0) * shrinkMsPerSecond / 1000
            if (step > 0) {
                targetDelayMs = maxOf(desired, targetDelayMs - step)
                shrinkAnchorMs = arrivalMs
            }
        }
        return senderMs + base + targetDelayMs
    }

    /** Forgets everything learned about the link. The next arrival starts a fresh estimate. */
    fun reset() {
        baseTransit.clear()
        lateness.clear()
        shrinkAnchorMs = Long.MIN_VALUE
        targetDelayMs = minDelayMs
        resets++
    }

    companion object {
        const val BUCKET_MS = 500L
        const val DEFAULT_MARGIN_MS = 30L
        /** How long a bad moment is remembered. Long, because a link that just dropped will again. */
        const val DEFAULT_JITTER_WINDOW_MS = 20_000L
        const val DEFAULT_BASE_WINDOW_MS = 30_000L
        const val DEFAULT_SHRINK_MS_PER_SECOND = 40L
        const val DEFAULT_RESET_THRESHOLD_MS = 10_000L
    }
}
