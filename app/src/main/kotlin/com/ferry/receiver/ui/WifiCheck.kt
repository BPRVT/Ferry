package com.ferry.receiver.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.ferry.receiver.R
import com.ferry.receiver.util.WifiAssessment
import com.ferry.receiver.util.WifiAssessment.Advice
import com.ferry.receiver.util.WifiAssessment.Band
import com.ferry.receiver.util.WifiAssessment.Signal

/**
 * Reads this TV's connection and words it for Settings → Weak Wi-Fi → Check my Wi-Fi.
 *
 * Needs only ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE, which Ferry already holds. It deliberately
 * does not read the network name: on Android 8.1+ that needs location permission, and nothing here
 * depends on it.
 */
object WifiCheck {

    fun message(context: Context): String {
        val app = context.applicationContext
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = connectivity?.let { cm -> cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
            return app.getString(R.string.wifi_check_ethernet)
        }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION") // still the only API for the connected AP's RSSI/rate before 31, and fine after
        val info = wifi?.connectionInfo
        if (info == null || caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return app.getString(R.string.wifi_check_offline)
        }

        val band = WifiAssessment.band(info.frequency)
        val rssi = info.rssi
        val link = info.linkSpeed
        val readout = app.getString(
            R.string.wifi_check_readout,
            app.getString(bandLabel(band)),
            app.getString(signalLabel(WifiAssessment.signal(rssi))),
            "$rssi dBm",
            "${link.coerceAtLeast(0)} Mbps",
        )
        val advice = WifiAssessment.advise(band, rssi, link).joinToString("\n\n") { app.getString(adviceText(it)) }
        return "$readout\n\n$advice"
    }

    private fun bandLabel(band: Band) = when (band) {
        Band.GHZ_2_4 -> R.string.wifi_band_24
        Band.GHZ_5 -> R.string.wifi_band_5
        Band.GHZ_6 -> R.string.wifi_band_6
        Band.UNKNOWN -> R.string.wifi_band_unknown
    }

    private fun signalLabel(signal: Signal) = when (signal) {
        Signal.EXCELLENT -> R.string.wifi_signal_excellent
        Signal.GOOD -> R.string.wifi_signal_good
        Signal.FAIR -> R.string.wifi_signal_fair
        Signal.WEAK -> R.string.wifi_signal_weak
        Signal.VERY_WEAK -> R.string.wifi_signal_very_weak
    }

    private fun adviceText(advice: Advice) = when (advice) {
        Advice.ON_2_4_GHZ -> R.string.wifi_advice_24ghz
        Advice.WEAK_SIGNAL -> R.string.wifi_advice_weak_signal
        Advice.SLOW_LINK -> R.string.wifi_advice_slow_link
        Advice.ETHERNET -> R.string.wifi_advice_ethernet
        Advice.SMOOTH_PLAYBACK -> R.string.wifi_advice_smooth
        Advice.HEALTHY -> R.string.wifi_advice_good
    }
}
