package com.ferry.receiver.util

/**
 * Turns the TV's Wi-Fi readings into plain advice for Settings → Weak Wi-Fi → Check my Wi-Fi.
 *
 * The point is the advice, not the numbers. On a connection that drops, the fixes that matter most
 * are physical — 5 GHz instead of 2.4, the router nearer, a cable — and no setting in Ferry beats
 * them. Someone looking at a stuttering picture has no way to know which of those applies to them;
 * this tells them.
 *
 * Pure, so the thresholds are tested off-device. The Android side that reads the radio lives in
 * `ui/WifiCheck`.
 */
object WifiAssessment {

    enum class Band { GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN }

    enum class Signal { EXCELLENT, GOOD, FAIR, WEAK, VERY_WEAK }

    enum class Advice { ON_2_4_GHZ, WEAK_SIGNAL, SLOW_LINK, ETHERNET, SMOOTH_PLAYBACK, HEALTHY }

    fun band(frequencyMhz: Int): Band = when (frequencyMhz) {
        in 2_400..2_500 -> Band.GHZ_2_4
        in 4_900..5_899 -> Band.GHZ_5
        in 5_925..7_125 -> Band.GHZ_6
        else -> Band.UNKNOWN
    }

    /** Conventional RSSI bands; mirroring starts to suffer around "weak". */
    fun signal(rssiDbm: Int): Signal = when {
        rssiDbm >= -55 -> Signal.EXCELLENT
        rssiDbm >= -67 -> Signal.GOOD
        rssiDbm >= -75 -> Signal.FAIR
        rssiDbm >= -82 -> Signal.WEAK
        else -> Signal.VERY_WEAK
    }

    /**
     * What to tell the user, most useful first.
     *
     * @param linkMbps the negotiated link rate, or ≤ 0 if the driver did not report one.
     */
    fun advise(band: Band, rssiDbm: Int, linkMbps: Int): List<Advice> {
        val problems = buildList {
            if (band == Band.GHZ_2_4) add(Advice.ON_2_4_GHZ)
            if (rssiDbm < WEAK_RSSI_DBM) add(Advice.WEAK_SIGNAL)
            if (linkMbps in 1 until SLOW_LINK_MBPS) add(Advice.SLOW_LINK)
        }
        if (problems.isEmpty()) return listOf(Advice.HEALTHY)
        return problems + Advice.ETHERNET + Advice.SMOOTH_PLAYBACK
    }

    /** Below this, the signal is the problem. */
    const val WEAK_RSSI_DBM = -70

    /**
     * A 1080p mirror runs a few to ~15 Mbps, but the negotiated rate is a best case that real
     * throughput falls well short of — under ~40 there is little headroom for a burst.
     */
    const val SLOW_LINK_MBPS = 40
}
