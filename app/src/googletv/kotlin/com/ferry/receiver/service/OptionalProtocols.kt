package com.ferry.receiver.service

import android.content.Context
import com.ferry.receiver.cast.CastReceiver
import com.ferry.receiver.miracast.MiracastReceiver
import com.ferry.receiver.settings.AppSettings
import com.ferry.receiver.util.Logger

/**
 * Google TV / Android TV: Miracast and Cast are both available alongside AirPlay.
 *
 * The flavor seam described in the firetv copy of this class. [FerryService] owns one of these and
 * knows nothing about which protocols exist behind it, so the Fire TV build can drop both without
 * the shared service code carrying dead branches.
 *
 * Cast here is the real Cast Connect receiver SDK, not the Fire TV stub — it needs Google Play
 * Services (present on this flavor) and a registered `CAST_APP_ID` (see docs/guides/CAST_APP_ID.md;
 * `CastReceiver.start` reports ERROR when it is unset).
 */
class OptionalProtocols(private val context: Context) {

    private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null

    fun start(
        settings: AppSettings,
        onMiracastState: (ProtocolState) -> Unit,
        onCastState: (ProtocolState) -> Unit,
    ) {
        if (settings.miracastEnabled) {
            miracastReceiver = MiracastReceiver(
                context = context,
                onStateChanged = onMiracastState
            ).also { it.start() }
            Logger.d("Miracast receiver started")
        } else {
            onMiracastState(ProtocolState.DISABLED)
        }

        if (settings.castEnabled) {
            castReceiver = CastReceiver(
                context = context,
                onStateChanged = onCastState
            ).also { it.start() }
            Logger.d("Cast receiver started")
        } else {
            onCastState(ProtocolState.DISABLED)
        }
    }

    fun stop() {
        try { miracastReceiver?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { castReceiver?.stop() } catch (e: Exception) { Logger.e("Cast stop error", e) }
        miracastReceiver = null
        castReceiver = null
    }

    companion object {
        const val MIRACAST_SUPPORTED = true
        const val CAST_SUPPORTED = true

        /** True when this build has any optional protocol at all — drives whether the UI shows them. */
        const val ANY_SUPPORTED = MIRACAST_SUPPORTED || CAST_SUPPORTED
    }
}
