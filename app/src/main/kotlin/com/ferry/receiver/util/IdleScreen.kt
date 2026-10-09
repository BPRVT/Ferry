package com.ferry.receiver.util

import kotlin.math.PI
import kotlin.math.sin

/**
 * What the streaming screen shows while the mirrored picture is not changing — Settings → Picture &
 * sound → Paused screen.
 *
 * ── Why ──
 *
 * When a video is paused on the iPad, iOS stops sending frames entirely; the TV keeps showing the
 * last one. Ferry is still connected — it answers the sender's keep-alive every couple of seconds,
 * keeps its timing exchange running, and since 8.0.0 never hangs up on its own — but a frozen
 * picture cannot say so, and a frozen picture is exactly what a crash looks like. So:
 *
 *  - after [CONNECTED_AFTER_MS] of stillness, a small "Still connected" note with a slowly pulsing
 *    dot, so the screen visibly has something alive on it;
 *  - after [DIM_AFTER_MS], the picture dims and the note drifts slowly around the screen. Ferry
 *    holds the TV awake for the whole session, which also keeps the TV's own screensaver away, so a
 *    paused frame could otherwise sit unchanged for hours — a real burn-in risk on OLED sets;
 *  - if the sender's video connection has actually closed, the note says so and how to recover.
 *    That is a socket closing, not a guess from silence, so a pause can never produce it.
 *
 * Every state clears the instant a new frame arrives; the dim also lifts on any remote button.
 *
 * Pure, so the timing is tested off-device.
 */
object IdleScreen {

    /** Which note to show, if any. */
    enum class Note {
        /** The picture is moving (or nothing has arrived yet): show nothing extra. */
        NONE,
        /** Still for a few seconds: "Still connected". */
        CONNECTED,
        /** The sender's video connection closed: say so, and how to recover. */
        LINK_LOST,
    }

    /**
     * What to show: [note], and whether the picture is [dimmed] with the note drifting. Dimming
     * applies to a lost connection too — that leaves a frozen frame on screen just the same, and
     * since 8.0.0 nothing ends the session on its own.
     */
    data class Look(val note: Note, val dimmed: Boolean) {
        companion object {
            val NOTHING = Look(Note.NONE, dimmed = false)
        }
    }

    /**
     * @param lastArrivalMs when a mirrored frame last arrived, 0 if none has this session (URL
     *   playback, or a session still starting) — in which case there is nothing to annotate.
     * @param lastActivityMs when the viewer last pressed a remote button, 0 if never; it lifts the dim.
     */
    fun look(
        nowMs: Long,
        enabled: Boolean,
        lastArrivalMs: Long,
        linkUp: Boolean,
        lastActivityMs: Long = 0L,
    ): Look {
        if (!enabled || lastArrivalMs <= 0L) return Look.NOTHING
        val still = nowMs - lastArrivalMs
        val note = when {
            !linkUp -> Note.LINK_LOST
            still >= CONNECTED_AFTER_MS -> Note.CONNECTED
            else -> return Look.NOTHING
        }
        val idle = nowMs - maxOf(lastArrivalMs, lastActivityMs)
        return Look(note, dimmed = idle >= DIM_AFTER_MS)
    }

    /**
     * Where the drifting note sits while dimmed, as fractions (0..1) of the free space in each
     * direction. Two slow sine sweeps with unrelated periods, so it wanders the whole screen over a
     * few minutes without ever jumping, and never parks anywhere long enough to mark the panel.
     */
    fun driftPosition(nowMs: Long): Pair<Float, Float> {
        val x = 0.5 + 0.5 * sin(2 * PI * nowMs / DRIFT_PERIOD_X_MS)
        val y = 0.5 + 0.5 * sin(2 * PI * nowMs / DRIFT_PERIOD_Y_MS)
        return x.toFloat() to y.toFloat()
    }

    /** Long enough that a picture between scene changes never triggers it. */
    const val CONNECTED_AFTER_MS = 5_000L

    /** Long enough not to bother anyone pausing for a drink; short enough to protect the panel. */
    const val DIM_AFTER_MS = 5 * 60_000L

    /** How dark the dimmed picture gets (alpha of the black layer over it). */
    const val DIM_ALPHA = 0.7f

    private const val DRIFT_PERIOD_X_MS = 97_000.0
    private const val DRIFT_PERIOD_Y_MS = 61_000.0
}
