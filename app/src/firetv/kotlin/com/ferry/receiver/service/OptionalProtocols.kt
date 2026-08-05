package com.ferry.receiver.service

import android.content.Context
import com.ferry.receiver.settings.AppSettings
import com.ferry.receiver.util.Logger

/**
 * Fire TV: there are no optional protocols. AirPlay is the whole receiver.
 *
 * This is the flavor seam that lets the Fire TV build contain no Miracast or Cast code at all —
 * [FerryService] talks only to this class, and the googletv source set supplies a different one
 * under the same fully-qualified name.
 *
 * Both protocols were shipped and enabled by default through 5.5.0, and neither ever worked here:
 *
 *  - **Cast** cannot work. Fire TV has no Google Play Services, so the Cast TV SDK has nothing to
 *    run against. The firetv `CastReceiver` was an honest no-op that logged a warning and reported
 *    DISABLED — but it still put a toggle in Settings and a card on the home screen that could
 *    never do anything.
 *  - **Miracast** was worse than inert. `MiracastReceiver.registerP2pService` gates on
 *    `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES`, and Ferry never requested either at runtime —
 *    `MainActivity` asks only for POST_NOTIFICATIONS. So the check failed on every Fire TV, on
 *    every boot, and the receiver emitted `ProtocolState.ERROR` without ever advertising or opening
 *    port 7236. A permanent red error card, for a protocol that never ran.
 *
 * Removing them also drops CHANGE_WIFI_STATE, the two capped location permissions and
 * NEARBY_WIFI_DEVICES from the Fire TV manifest — permissions the app asked for and never used.
 */
class OptionalProtocols(@Suppress("UNUSED_PARAMETER") context: Context) {

    /**
     * No-op. Reports DISABLED for both so any state the UI still holds is cleared rather than left
     * showing whatever the last run put there.
     */
    fun start(
        @Suppress("UNUSED_PARAMETER") settings: AppSettings,
        onMiracastState: (ProtocolState) -> Unit,
        onCastState: (ProtocolState) -> Unit,
    ) {
        Logger.d("No optional protocols on Fire TV — AirPlay only")
        onMiracastState(ProtocolState.DISABLED)
        onCastState(ProtocolState.DISABLED)
    }

    /** No-op — nothing was ever started. */
    fun stop() = Unit

    companion object {
        const val MIRACAST_SUPPORTED = false
        const val CAST_SUPPORTED = false

        /** True when this build has any optional protocol at all — drives whether the UI shows them. */
        const val ANY_SUPPORTED = MIRACAST_SUPPORTED || CAST_SUPPORTED
    }
}
