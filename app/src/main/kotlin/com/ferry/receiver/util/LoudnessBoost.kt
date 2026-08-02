package com.ferry.receiver.util

import android.media.audiofx.LoudnessEnhancer

/**
 * LoudnessBoost — optional post-gain on an [android.media.AudioTrack], for when the source itself
 * is quiet and the TV is already turned up.
 *
 * WHY NOT JUST setVolume: `AudioTrack.setVolume` is hard-capped at 1.0 — unity. It can only ever
 * attenuate, so it cannot make anything louder than the stream already is. Getting above unity
 * needs either per-sample multiplication in Kotlin (real work on the playback thread, at 44.1 kHz
 * stereo, on a modest TV SoC) or the platform's own effects framework. This uses the latter:
 * [LoudnessEnhancer] applies its gain down in the audio HAL and, importantly, compresses rather
 * than simply scaling — so a +12 dB boost on already-loud material does not just clip into
 * distortion the way a naive multiply would.
 *
 * Attached to an AudioTrack's session id and driven by [sync], which is cheap enough to call on
 * every packet: it does nothing at all unless the requested gain actually changed. That is what
 * lets the Settings slider take effect on live audio rather than at the next session.
 *
 * Not thread-safe by design — each audio path owns one instance and touches it only from its own
 * playback thread.
 */
class LoudnessBoost {

    private var enhancer: LoudnessEnhancer? = null
    private var attachedSession = AudioManagerNoSession
    private var appliedDb = 0

    /**
     * Applies [targetDb] of boost to [sessionId], creating or tearing down the underlying effect as
     * needed. A no-op when nothing has changed, so it is safe to call per packet.
     *
     * At 0 dB the effect is released rather than set to zero gain: an attached-but-idle effect
     * still sits in the output path, and there is no reason to keep it there when the user has the
     * feature off (which is the default).
     *
     * Every platform call is wrapped — the effects framework is optional on Android and a device
     * that lacks it, or that refuses another effect because the global slot budget is exhausted,
     * throws rather than returning an error. Losing the boost is not worth losing the audio.
     */
    fun sync(sessionId: Int, targetDb: Int) {
        val wanted = AudioGain.clampBoostDb(targetDb)
        if (wanted == appliedDb && sessionId == attachedSession) return

        if (sessionId != attachedSession) release()   // track was recreated — old effect is stale

        if (wanted == 0) {
            release()
            appliedDb = 0
            return
        }

        val fx = enhancer ?: runCatching { LoudnessEnhancer(sessionId) }
            .onFailure { Logger.w("Audio boost unavailable on this device — ${it.message}") }
            .getOrNull()
            ?.also { enhancer = it; attachedSession = sessionId }
            ?: return

        runCatching {
            fx.setTargetGain(wanted * 100)   // LoudnessEnhancer takes millibels; 100 mB = 1 dB
            fx.enabled = true
            appliedDb = wanted
            Logger.i("Audio boost set to +$wanted dB (session $sessionId)")
        }.onFailure { Logger.w("Audio boost could not be applied — ${it.message}") }
    }

    /** Releases the effect. Safe to call repeatedly and when nothing is attached. */
    fun release() {
        runCatching { enhancer?.enabled = false }
        runCatching { enhancer?.release() }
        enhancer = null
        attachedSession = AudioManagerNoSession
        appliedDb = 0
    }

    private companion object {
        /** Sentinel for "not attached to any audio session yet". Real session ids are positive. */
        const val AudioManagerNoSession = 0
    }
}
