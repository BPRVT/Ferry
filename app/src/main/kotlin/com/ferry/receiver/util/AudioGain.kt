package com.ferry.receiver.util

import kotlin.math.pow

/**
 * AudioGain — conversions between the AirPlay volume scale and a linear amplitude multiplier.
 *
 * WHY THIS EXISTS: AirPlay reports volume in **decibels** (SET_PARAMETER `volume: <dB>`), over the
 * range −30 dB … 0 dB, with −144 meaning mute. `AudioTrack.setVolume` takes a **linear amplitude**
 * multiplier in 0.0 … 1.0. Those are not the same scale, and mapping one onto the other linearly —
 * as this code used to — makes the sender's volume slider wrong everywhere except at its two ends:
 *
 * | sender slider | dB     | correct amplitude | old linear map | error   |
 * |---------------|--------|-------------------|----------------|---------|
 * | max           | 0      | 1.000             | 1.000          | none    |
 * | 3/4           | −7.5   | 0.422             | 0.750          | +5.0 dB |
 * | middle        | −15    | 0.178             | 0.500          | +9.0 dB |
 * | 1/4           | −22.5  | 0.075             | 0.250          | +10.5 dB|
 * | min           | −30    | 0.032             | 0.000          | −∞      |
 *
 * The audible effect is a slider that does almost nothing over its top half and then collapses
 * near the bottom. Note the ends were already correct, which is why this is invisible if you only
 * ever listen at full volume.
 *
 * Pure functions with no Android dependency, so they are unit-testable in the JVM test-runner.
 * The boost side (which needs the platform effects framework) lives in [LoudnessBoost].
 */
object AudioGain {

    /** AirPlay's "silence" sentinel. Any value at or below this means mute, not a quiet level. */
    const val MUTE_DB = -144f

    /** Quietest non-muted level AirPlay reports. */
    const val MIN_DB = -30f

    /** Ceiling for the user-facing audio boost, in dB. */
    const val MAX_BOOST_DB = 12

    /** The boost values offered in Settings. 0 = off. */
    val BOOST_STEPS = intArrayOf(0, 3, 6, 9, 12)

    /**
     * Converts an AirPlay volume in dB to the linear amplitude multiplier `AudioTrack.setVolume`
     * expects.
     *
     * @param airplayVolumeDb volume as reported by the sender (−30 … 0, or ≤ [MUTE_DB] for mute)
     * @return amplitude in 0.0 … 1.0
     */
    fun amplitudeFor(airplayVolumeDb: Float): Float {
        if (airplayVolumeDb <= MUTE_DB) return 0f
        // Anything below the nominal floor is clamped rather than driven to zero: senders do
        // occasionally report slightly out-of-range values, and treating those as mute would cut
        // the audio out entirely instead of playing it quietly.
        val db = airplayVolumeDb.coerceIn(MIN_DB, 0f)
        return 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f)
    }

    /** Clamps a stored boost setting to a value this build is willing to apply. */
    fun clampBoostDb(db: Int): Int = db.coerceIn(0, MAX_BOOST_DB)
}
