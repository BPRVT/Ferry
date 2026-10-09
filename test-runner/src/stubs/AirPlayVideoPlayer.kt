package com.ferry.receiver.airplay

import android.content.Context
import android.view.Surface

/**
 * AirPlayVideoPlayer stub for JVM test compilation.
 *
 * The real class is built on Media3 ExoPlayer, which is published only to Google's Maven
 * repository, and the test runner resolves from Maven Central alone. Nothing under test drives
 * URL playback, so this only needs the same surface as the real class for AirPlayReceiver to
 * compile.
 */
@Suppress("UNUSED_PARAMETER")
class AirPlayVideoPlayer(
    context: Context,
    surfaceProvider: () -> Surface?,
    onEnded: () -> Unit = {},
) {
    fun play(url: String, startPositionFraction: Double) {}
    fun setRate(rate: Float) {}
    fun scrub(positionSec: Double) {}
    fun info(): PlaybackInfo? = null
    fun attachSurface() {}
    fun release() {}
}
