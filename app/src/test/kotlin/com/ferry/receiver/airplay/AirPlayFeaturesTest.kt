package com.ferry.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The features bitmask is what a sender uses to decide whether to mirror or to hand off a media
 * URL, and it is published in three places that have to agree. These tests pin the exact wire
 * values: a change here is a change in how every sender on the network treats Ferry.
 */
class AirPlayFeaturesTest {

    @Test
    fun `full bitmask keeps the historical wire value`() {
        assertEquals(0x1E5A7FFFF7L, AirPlayFeatures.FULL)
        assertEquals("0x5A7FFFF7,0x1E", AirPlayFeatures.txtRecord(forceScreenMirroring = false))
    }

    @Test
    fun `mirror-only clears the video bit and nothing else`() {
        assertEquals(0x1E5A7FFFF6L, AirPlayFeatures.MIRROR_ONLY)
        assertEquals("0x5A7FFFF6,0x1E", AirPlayFeatures.txtRecord(forceScreenMirroring = true))

        // Exactly one bit of difference, and it is bit 0.
        assertEquals(1L, AirPlayFeatures.FULL xor AirPlayFeatures.MIRROR_ONLY)
    }

    @Test
    fun `video bit is set only when mirroring is not forced`() {
        assertEquals(1L, AirPlayFeatures.value(forceScreenMirroring = false) and 1L)
        assertEquals(0L, AirPlayFeatures.value(forceScreenMirroring = true) and 1L)
        assertNotEquals(
            AirPlayFeatures.value(forceScreenMirroring = false),
            AirPlayFeatures.value(forceScreenMirroring = true)
        )
    }

    @Test
    fun `screen mirroring stays advertised in both modes`() {
        // Bit 7 is SupportsAirPlayScreen. Forcing mirroring must not disturb it — clearing it
        // would take Ferry out of the Control Center picker entirely, which is the exact
        // opposite of what the setting is for.
        val screenBit = 1L shl 7
        assertEquals(screenBit, AirPlayFeatures.value(forceScreenMirroring = false) and screenBit)
        assertEquals(screenBit, AirPlayFeatures.value(forceScreenMirroring = true) and screenBit)
    }

    @Test
    fun `txt record splits into low then high 32-bit halves`() {
        // The high half must survive the split — a naive Int cast would drop it and senders would
        // see a device with no AirPlay 2 support at all.
        listOf(true, false).forEach { forced ->
            val (low, high) = AirPlayFeatures.txtRecord(forced).split(",")
            val recombined = (high.removePrefix("0x").toLong(16) shl 32) or
                    low.removePrefix("0x").toLong(16)
            assertEquals(AirPlayFeatures.value(forced), recombined)
        }
    }
}
