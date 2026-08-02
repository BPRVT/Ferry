package com.ferry.receiver.util

import timber.log.Timber

/**
 * Logger — A thin wrapper around Timber for Ferry-specific logging.
 *
 * WHY: Using a wrapper instead of calling Timber directly gives us two benefits:
 * 1. We can add Ferry-specific behavior in one place (e.g., scrubbing IP addresses
 *    from logs in release builds to protect user privacy)
 * 2. In tests, we can verify that specific log messages were emitted
 *
 * HOW: Use Logger.d/i/w/e throughout the codebase instead of Timber or Log directly.
 * Timber must be initialized in FerryApp.onCreate() before Logger is used.
 *
 * Example:
 *   Logger.d("RTSP connection from ${clientIp}")
 *   Logger.i("mDNS service registered: $serviceName")
 *   Logger.w("Audio buffer underrun — dropping frame")
 *   Logger.e("Unexpected socket error", exception)
 */
object Logger {

    /**
     * Logs a verbose message (very detailed, for debugging only).
     * Only emitted in debug builds.
     *
     * @param message The log message.
     */
    fun v(message: String) = Timber.v(message)

    /**
     * Verbose log whose message is only built if something is listening.
     *
     * Prefer this on any path that runs per frame, per packet, or per byte. The [String] overload
     * evaluates its argument at the call site, so in release — where [com.ferry.receiver.FerryApp]
     * deliberately plants no tree — the interpolation still allocates a StringBuilder and a String
     * that Timber then throws away. That is invisible on a lifecycle event and expensive in a loop:
     * RtpInterleaved's resync loop calls this once per skipped *byte*.
     *
     * Inline + lambda means the message expression is never evaluated when no tree is planted.
     */
    inline fun v(message: () -> String) {
        if (Timber.treeCount > 0) Timber.v(message())
    }

    /**
     * Logs a debug message (development-time information).
     * Only emitted in debug builds.
     *
     * @param message The log message.
     */
    fun d(message: String) = Timber.d(message)

    /** Debug log whose message is only built if a tree is planted. See [v] for why. */
    inline fun d(message: () -> String) {
        if (Timber.treeCount > 0) Timber.d(message())
    }

    /**
     * Logs an informational message (key lifecycle events, state changes).
     * Emitted in both debug and release builds (if a release logging backend is planted).
     *
     * @param message The log message.
     */
    fun i(message: String) = Timber.i(message)

    /**
     * Logs a warning (something unexpected happened but we can recover).
     * Emitted in both debug and release builds.
     *
     * @param message The log message.
     */
    fun w(message: String) = Timber.w(message)

    /**
     * Logs an error with an exception.
     * Emitted in both debug and release builds.
     *
     * RULE 4: Every exception must be logged. This method is the correct way to log
     * exceptions — it captures the stack trace for debugging.
     *
     * @param message Human-readable description of what failed.
     * @param throwable The exception that caused the error.
     */
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.e(throwable, message)
        } else {
            Timber.e(message)
        }
    }
}
