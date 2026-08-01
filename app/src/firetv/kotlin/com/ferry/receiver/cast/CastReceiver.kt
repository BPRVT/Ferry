package com.ferry.receiver.cast

import android.content.Context
import com.ferry.receiver.BuildConfig
import com.ferry.receiver.service.ProtocolState
import com.ferry.receiver.util.Logger

/**
 * Fire TV Cast receiver implementation.
 *
 * Amazon Fire TV does not ship Google Play Services, so the official Google
 * Cast TV SDK cannot run on this flavor. The receiver reports DISABLED instead
 * of pretending to advertise a protocol it cannot actually serve.
 */
class CastReceiver(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {
    fun start() {
        Logger.w("Google Cast disabled on Fire TV flavor")
        onStateChanged(ProtocolState.DISABLED)
    }

    fun stop() {
        onStateChanged(ProtocolState.DISABLED)
    }

    companion object {
        fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

        fun isConfigured(appId: String = CAST_APP_ID): Boolean {
            val normalized = appId.trim()
            return normalized.isNotEmpty() &&
                normalized != "TODO_REGISTER_YOUR_CAST_APP_ID" &&
                normalized != "00000000"
        }

        const val CAST_APP_ID = BuildConfig.CAST_APP_ID
    }
}
