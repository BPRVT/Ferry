package com.ferry.receiver.service

/**
 * PhotoFrame — latest still image received via AirPlay `/photo`.
 *
 * The bytes are kept in memory only and cleared on DELETE `/photo`, streaming
 * start, receiver stop, or service destruction.
 *
 * Moved verbatim out of FerryService.kt, unchanged. It is a plain data holder with no
 * Android dependencies, but the JVM `test-runner` module excludes FerryService.kt
 * (which needs NotificationCompat and the generated R class), so anything there that
 * referenced PhotoFrame could not compile. Keeping it in its own file lets
 * [isSessionActive] and its tests build in both modules.
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
