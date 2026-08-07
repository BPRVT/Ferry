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
 * **The gain itself costs Ferry nothing.** It runs in the audio HAL, not on the playback thread, so
 * turning it up does not make the receiver do more work per packet. What *did* cost something was
 * failing to turn it on — see [failedDb].
 *
 * Attached to an AudioTrack's session id and driven by [sync], which is cheap enough to call on
 * every packet: it does nothing at all unless the requested gain actually changed. That is what
 * lets the Settings slider take effect on live audio rather than at the next session.
 *
 * Not thread-safe by design — each audio path owns one instance and touches it only from its own
 * playback thread.
 *
 * @param open opens the platform effect for a session, or throws. Injectable so the retry policy
 *   below can be tested without an audio HAL — the failure it guards against is one that only
 *   happens on devices that refuse the effect, which is exactly what a test has to be able to stage.
 */
class LoudnessBoost(private val open: (Int) -> Effect = ::platformEffect) {

    /**
     * The slice of [LoudnessEnhancer] this class actually uses.
     *
     * Exists so the policy in [sync] — when to attach, when to give up, when to try again — is
     * separable from the platform call it drives.
     */
    interface Effect {
        fun setGainMillibels(millibels: Int)
        fun enable()
        fun close()
    }

    private var effect: Effect? = null
    private var attachedSession = NO_SESSION
    private var appliedDb = 0

    /**
     * The (session, gain) pair that already failed, so it is not attempted again on the next packet.
     *
     * Without this, [sync] retried the whole attach on **every packet** for the rest of the session
     * whenever it could not apply the boost — roughly 92 times a second, on the playback thread,
     * each attempt a `LoudnessEnhancer` construction that binders into audioserver, throws, builds
     * an exception, and logs a warning. The early return that is supposed to make [sync] free to
     * call per packet compares [appliedDb] against the request, and a failure is precisely the case
     * where [appliedDb] never reaches it.
     *
     * Only reachable with the boost **on**: at 0 dB the request and [appliedDb] agree, so the
     * failure path was never entered at all. Which made it a defect that bit only the people who
     * turned the feature on, on the one thread where a stall is audible.
     *
     * Cleared whenever the request changes — a different gain, or a new AudioTrack — because those
     * are genuinely new attempts, and the usual reason for the earlier failure (the device's global
     * effect-slot budget) may no longer hold.
     */
    private var failedSession = NO_SESSION
    private var failedDb = NOT_ATTEMPTED

    /**
     * What the boost is *actually* doing, for the debug overlay: the gain in dB if it is applied, 0
     * if it is off, or [UNAVAILABLE] if it was asked for and the device refused.
     *
     * Worth surfacing because the setting and the effect can disagree and nothing said so. A user
     * with the slider at +12 dB on a device whose effect framework refuses it hears no boost, has
     * no way to find out why (Ferry runs on a TV with no adb, so a logcat warning does not exist),
     * and would reasonably conclude the feature does nothing.
     */
    val state: Int get() = if (failedDb > 0) UNAVAILABLE else appliedDb

    /**
     * Applies [targetDb] of boost to [sessionId], creating or tearing down the underlying effect as
     * needed. A no-op when nothing has changed, so it is safe to call per packet.
     *
     * At 0 dB the effect is released rather than set to zero gain: an attached-but-idle effect still
     * sits in the output path, and there is no reason to keep it there when the user has the feature
     * off (which is the default).
     *
     * Every platform call is wrapped — the effects framework is optional on Android, and a device
     * that lacks it, or that refuses another effect because the global slot budget is exhausted,
     * throws rather than returning an error. Losing the boost is not worth losing the audio.
     */
    fun sync(sessionId: Int, targetDb: Int) {
        val wanted = AudioGain.clampBoostDb(targetDb)
        if (wanted == appliedDb && sessionId == attachedSession) return

        // Track was recreated — the effect is attached to the old session and is now stale.
        // Conditional on there being one: an attach that *failed* never set [attachedSession], so an
        // unconditional release here would run on every packet. An earlier version of this fix also
        // cleared the failure record at this point, which meant the record was wiped every call and
        // the retry guard below never once fired. Invalidating a stale failure is not this branch's
        // job and does not need to be: the guard keys on [failedSession] and [failedDb], so a new
        // session or a new gain simply does not match it, and falls through to a fresh attempt.
        if (effect != null && sessionId != attachedSession) release()

        if (wanted == 0) {
            release()
            appliedDb = 0
            return
        }

        // This exact request already failed. Do not spend a binder round trip on it once per packet
        // for the rest of the session — see [failedDb].
        if (sessionId == failedSession && wanted == failedDb) return

        val fx = effect ?: runCatching { open(sessionId) }
            .onFailure { markFailed(sessionId, wanted, "unavailable on this device — ${it.message}") }
            .getOrNull()
            ?.also { effect = it; attachedSession = sessionId }
            ?: return

        runCatching {
            fx.setGainMillibels(wanted * 100)   // LoudnessEnhancer takes millibels; 100 mB = 1 dB
            fx.enable()
        }.onSuccess {
            appliedDb = wanted
            failedDb = NOT_ATTEMPTED
            Logger.i("Audio boost set to +$wanted dB (session $sessionId)")
        }.onFailure {
            // The effect exists but will not take the gain, so it is useless and still sitting in
            // the output path. Drop it rather than leave a dead block between the decoder and the
            // DAC — and record the failure, or the next packet arrives and tries the whole thing
            // again, which is the storm this all exists to prevent.
            release()
            markFailed(sessionId, wanted, "could not be applied — ${it.message}")
        }
    }

    /** Records a failed attach so [sync] stops retrying it, and says so once rather than per packet. */
    private fun markFailed(sessionId: Int, db: Int, detail: String) {
        val alreadyReported = sessionId == failedSession && db == failedDb
        failedSession = sessionId
        failedDb = db
        if (!alreadyReported) {
            Logger.w("Audio boost $detail — playing at normal level for this session")
        }
    }

    /** Releases the effect. Safe to call repeatedly and when nothing is attached. */
    fun release() {
        runCatching { effect?.close() }
        effect = null
        attachedSession = NO_SESSION
        appliedDb = 0
    }

    internal companion object {
        /** Sentinel for "not attached to any audio session yet". Real session ids are positive. */
        const val NO_SESSION = 0

        /** Sentinel for "no attach has failed yet". A real request is 0…[AudioGain.MAX_BOOST_DB]. */
        const val NOT_ATTEMPTED = -1

        /** [state] value meaning "a boost was requested and this device would not give us one". */
        const val UNAVAILABLE = -1
    }
}

/** The real thing: a [LoudnessEnhancer] on [sessionId]. Throws if the platform refuses it. */
private fun platformEffect(sessionId: Int): LoudnessBoost.Effect {
    val enhancer = LoudnessEnhancer(sessionId)
    return object : LoudnessBoost.Effect {
        override fun setGainMillibels(millibels: Int) = enhancer.setTargetGain(millibels)
        override fun enable() { enhancer.enabled = true }
        override fun close() {
            runCatching { enhancer.enabled = false }
            enhancer.release()
        }
    }
}
