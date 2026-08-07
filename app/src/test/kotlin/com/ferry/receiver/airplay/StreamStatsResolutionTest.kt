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

    /** The case the whole function exists for: the setting was changed and the sender ignored it. */
    @Test
    fun `a request the sender ignored shows both, actual first`() {
        StreamStats.videoAdvertised = "1280x720"
        StreamStats.videoWidth = 1920
        StreamStats.videoHeight = 1080
        assertEquals("1920x1080 (asked 1280x720)", StreamStats.resolution())
    }

    /** A portrait phone mirroring is not a mismatch worth hiding — it is reported as it is. */
    @Test
    fun `a portrait source reports its real shape`() {
        StreamStats.videoAdvertised = "1920x1080"
        StreamStats.videoWidth = 886
        StreamStats.videoHeight = 1920
        assertEquals("886x1920 (asked 1920x1080)", StreamStats.resolution())
    }
}
