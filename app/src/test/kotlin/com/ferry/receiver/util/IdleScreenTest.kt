package com.ferry.receiver.util

import com.ferry.receiver.util.IdleScreen.Look
import com.ferry.receiver.util.IdleScreen.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleScreenTest {

    private val t = 1_800_000_000_000L   // wall-clock millis, as StreamStats records them

    private fun look(stillMs: Long, linkUp: Boolean = true, enabled: Boolean = true, activityAgoMs: Long? = null) =
        IdleScreen.look(
            nowMs = t,
            enabled = enabled,
            lastArrivalMs = t - stillMs,
            linkUp = linkUp,
            lastActivityMs = activityAgoMs?.let { t - it } ?: 0L,
        )

    @Test
    fun `a moving picture shows nothing extra`() {
        assertEquals(Look.NOTHING, look(stillMs = 16))
        assertEquals(Look.NOTHING, look(stillMs = 4_999))
    }

    @Test
    fun `a paused picture says it is still connected`() {
        assertEquals(Look(Note.CONNECTED, dimmed = false), look(stillMs = 5_000))
        assertEquals(Look(Note.CONNECTED, dimmed = false), look(stillMs = 4 * 60_000))
    }

    @Test
    fun `a long pause dims to protect the panel`() {
        assertEquals(Look(Note.CONNECTED, dimmed = true), look(stillMs = 5 * 60_000))
        assertEquals(Look(Note.CONNECTED, dimmed = true), look(stillMs = 3 * 3_600_000))
    }

    @Test
    fun `a remote button lifts the dim but the note stays`() {
        assertEquals(Look(Note.CONNECTED, dimmed = false), look(stillMs = 20 * 60_000, activityAgoMs = 1_000))
        assertEquals(Look(Note.CONNECTED, dimmed = true), look(stillMs = 20 * 60_000, activityAgoMs = 6 * 60_000))
    }

    @Test
    fun `a closed video connection says so straight away, and dims later like any still picture`() {
        assertEquals(Look(Note.LINK_LOST, dimmed = false), look(stillMs = 100, linkUp = false))
        assertEquals(Look(Note.LINK_LOST, dimmed = true), look(stillMs = 10 * 60_000, linkUp = false))
    }

    @Test
    fun `nothing before the first frame, or with the setting off`() {
        assertEquals(Look.NOTHING, IdleScreen.look(t, enabled = true, lastArrivalMs = 0L, linkUp = false))
        assertEquals(Look.NOTHING, look(stillMs = 30 * 60_000, enabled = false))
        assertEquals(Look.NOTHING, look(stillMs = 100, linkUp = false, enabled = false))
    }

    @Test
    fun `the drifting note stays on screen and keeps moving`() {
        var previous = IdleScreen.driftPosition(0)
        var moved = 0
        for (s in 1..600) {
            val p = IdleScreen.driftPosition(s * 1_000L)
            assertTrue(p.first in 0f..1f && p.second in 0f..1f)
            if (p != previous) moved++
            previous = p
        }
        assertTrue("barely moved: $moved of 600 seconds", moved > 590)
    }
}
