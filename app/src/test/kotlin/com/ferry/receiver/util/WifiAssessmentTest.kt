package com.ferry.receiver.util

import com.ferry.receiver.util.WifiAssessment.Advice
import com.ferry.receiver.util.WifiAssessment.Band
import com.ferry.receiver.util.WifiAssessment.Signal
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiAssessmentTest {

    @Test
    fun `bands by frequency`() {
        assertEquals(Band.GHZ_2_4, WifiAssessment.band(2_437))
        assertEquals(Band.GHZ_5, WifiAssessment.band(5_180))
        assertEquals(Band.GHZ_6, WifiAssessment.band(5_955))
        assertEquals(Band.UNKNOWN, WifiAssessment.band(-1))
    }

    @Test
    fun `signal words by strength`() {
        assertEquals(Signal.EXCELLENT, WifiAssessment.signal(-48))
        assertEquals(Signal.GOOD, WifiAssessment.signal(-62))
        assertEquals(Signal.FAIR, WifiAssessment.signal(-72))
        assertEquals(Signal.WEAK, WifiAssessment.signal(-80))
        assertEquals(Signal.VERY_WEAK, WifiAssessment.signal(-90))
    }

    @Test
    fun `a strong 5 GHz link gets a clean bill of health`() {
        assertEquals(listOf(Advice.HEALTHY), WifiAssessment.advise(Band.GHZ_5, -58, 433))
    }

    @Test
    fun `a far-away 2_4 GHz link gets every relevant fix, worst first`() {
        assertEquals(
            listOf(Advice.ON_2_4_GHZ, Advice.WEAK_SIGNAL, Advice.SLOW_LINK, Advice.ETHERNET, Advice.SMOOTH_PLAYBACK),
            WifiAssessment.advise(Band.GHZ_2_4, -78, 26),
        )
    }

    @Test
    fun `an unreported link speed is not treated as slow`() {
        assertEquals(listOf(Advice.HEALTHY), WifiAssessment.advise(Band.GHZ_5, -60, -1))
    }
}
