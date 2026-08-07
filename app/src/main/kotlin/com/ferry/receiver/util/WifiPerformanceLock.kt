package com.ferry.receiver.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * WifiPerformanceLock — keeps the Wi-Fi radio out of power-save while Ferry is receiving.
 *
 * ── Why a receiver needs this ──
 *
 * Android's Wi-Fi driver puts the radio into power-save whenever it thinks nothing needs it, which
 * for a streaming *receiver* is a bad guess: the app is not transmitting, so from the driver's point
 * of view it is idle, while in fact it is the endpoint of a 60 fps video stream. In power-save the
 * radio sleeps between beacon intervals and wakes to collect what queued up, which converts steady
 * traffic into bursts — latency spikes, a frame's worth of video arriving late and then three at
 * once. On a marginal link the effect compounds, because retries land in the same duty cycle.
 *
 * The receiver above this handles bursts as well as it can — a bounded queue, a drop policy that
 * prefers frames nothing references — but that is damage control. The point of the lock is that the
 * bursts need not happen.
 *
 * This is also why a *sender* on the same network can look fine while the TV does not. An iPad
 * playing the video is transmitting constantly and is never a candidate for this; a stick sitting on
 * mains power quietly receiving is exactly the case the heuristic targets.
 *
 * ── Which mode ──
 *
 * `WIFI_MODE_FULL_HIGH_PERF` is the one that disables power-save outright, and it is what Fire OS 7
 * (API 25–28, most sticks in the field) understands. It was deprecated in API 29 in favour of
 * `WIFI_MODE_FULL_LOW_LATENCY`, which does the same thing and additionally asks the driver to
 * optimise for latency over throughput — and which is only honoured while the app is foreground with
 * the screen on. That is precisely Ferry's state during a cast (the streaming activity holds
 * FLAG_KEEP_SCREEN_ON), so the newer mode is used wherever it exists and the older one below it.
 *
 * ── Cost ──
 *
 * Power, and only power. Ferry runs on a mains-powered stick, and the lock is held only while the
 * receivers are actually up — it goes away with them.
 *
 * Everything here is best-effort: [WifiManager] can be absent (Ferry also runs on Ethernet-connected
 * boxes, where the whole question is moot) and `createWifiLock` is permission-gated, so a failure to
 * take the lock must never be a failure to stream.
 */
class WifiPerformanceLock(context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lock: WifiManager.WifiLock? = null

    /** Takes the lock, if the device has Wi-Fi and lets us. Idempotent. */
    @Synchronized
    fun acquire() {
        if (lock?.isHeld == true) return
        val manager = wifiManager ?: run {
            Logger.d("Wi-Fi lock: no WifiManager (wired or unsupported device) — skipping")
            return
        }
        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val mode = if (lowLatency) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        runCatching {
            manager.createWifiLock(mode, LOCK_TAG).also {
                it.setReferenceCounted(false)
                it.acquire()
                lock = it
            }
        }.onSuccess {
            Logger.i("Wi-Fi lock acquired (${if (lowLatency) "LOW_LATENCY" else "HIGH_PERF"}) — " +
                "radio power-save off while receiving")
        }.onFailure {
            Logger.w("Wi-Fi lock unavailable — streaming anyway (${it.message})")
        }
    }

    /** Gives the lock back. Idempotent, and safe to call when it was never taken. */
    @Synchronized
    fun release() {
        val held = lock ?: return
        runCatching { if (held.isHeld) held.release() }
            .onFailure { Logger.w("Wi-Fi lock release failed (non-fatal) — ${it.message}") }
        lock = null
        Logger.i("Wi-Fi lock released")
    }

    private companion object {
        /** Shows up in `dumpsys wifi` next to whoever else is holding one. */
        const val LOCK_TAG = "Ferry:receiver"
    }
}
