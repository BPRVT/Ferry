package com.ferry.receiver.airplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the HUD's resolution readout.
 *
 * This is the only way a user can check whether the resolution setting did anything. The advertised
 * size is a *request* in the AirPlay `/info` record and the sender decides whether to honour it, so
 * a readout that echoed the request would report success unconditionally — including in the one case
 * that matters, where the setting was changed and nothing happened.
 */
class StreamStatsResolutionTest {

    @After
    fun reset() {
        StreamStats.resetStreams()
    }

    @Test
    fun `before any frame is decoded, the advertised size is all there is`() {
        StreamStats.videoAdvertised = "1280x720"
        StreamStats.videoWidth = 0
        StreamStats.videoHeight = 0
        assertEquals("1280x720", StreamStats.resolution())
    }

    @Test
    fun `nothing known at all reads as a dash rather than blank`() {
        assertEquals("—", StreamStats.resolution())
    }

    @Test
    fun `a request the sender honoured shows the size once`() {
        StreamStats.videoAdvertised = "1280x720"
        StreamStats.videoWidth = 1280
        StreamStats.videoHeight = 720
        assertEquals("1280x720", StreamStats.resolution())
    }

    /**
     * Reported from hardware against 7.1.0, and the reason this is containment rather than equality.
     *
     * An iPad told "1280x720" sends **1046x720** — its own 4:3-ish panel fitted to that height. The
     * cap was honoured exactly as intended. Calling that a refusal would send the user hunting for a
     * setting that did apply, which is worse than showing nothing at all.
     */
    @Test
    fun `a sender fitting its own aspect ratio inside the box has honoured the request`() {
        StreamStats.videoAdvertised = "1280x720"
        StreamStats.videoWidth = 1046
        StreamStats.videoHeight = 720
        assertEquals("1046x720", StreamStats.resolution())
    }

    /** Portrait swaps the axes and still fits the same box — orientation is not a refusal either. */
    @Test
    fun `a portrait source inside the box is not flagged`() {
        StreamStats.videoAdvertised = "1920x1080"
        StreamStats.videoWidth = 886
        StreamStats.videoHeight = 1920
        assertEquals("886x1920", StreamStats.resolution())
    }

    /** A genuine refusal: the sender came back bigger than the box it was offered. */
    @Test
    fun `a sender exceeding the box is flagged`() {
        StreamStats.videoAdvertised = "1280x720"
        StreamStats.videoWidth = 1920
        StreamStats.videoHeight = 1080
        assertEquals("1920x1080 (asked 1280x720)", StreamStats.resolution())
    }

    /** A malformed advertised string must never produce a false accusation. */
    @Test
    fun `an unparseable advertised size is never called a refusal`() {
        StreamStats.videoAdvertised = "unknown"
        StreamStats.videoWidth = 1046
        StreamStats.videoHeight = 720
        assertEquals("1046x720", StreamStats.resolution())
    }
}
