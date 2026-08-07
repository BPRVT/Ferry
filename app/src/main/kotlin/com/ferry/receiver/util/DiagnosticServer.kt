package com.ferry.receiver.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * DiagnosticServer — serves the crash/freeze report over the LAN, so it can be copied off the TV.
 *
 * ── Why this exists ──
 *
 * `CrashReporter` records why Ferry died and `DiagnosticScreen` puts it on the television, which is
 * enough to read but not to *send*: a stack trace photographed off a TV is transcribable in theory
 * and miserable in practice. This makes the same report fetchable from a phone or laptop browser on
 * the same network, as selectable text.
 *
 * ── What it deliberately is not ──
 *
 * It does not upload anything, anywhere. Ferry has no internet permission beyond the LAN it mirrors
 * over, no account, and no third-party service, and none of that changes for the convenience of a
 * bug report. The device serves the file; the user decides who sees it. That also means there is no
 * endpoint of mine for the TV to send to — the last step is always the user pasting the text.
 *
 * ── Scope, because it is a listening socket ──
 *
 * Runs **only while the user is looking at the diagnostics screen**, and stops itself after
 * [MAX_LIFETIME_MS] regardless. It is read-only, serves exactly one document, and is bound to an
 * OS-assigned port rather than a predictable one. The report contains stack traces, log lines and
 * the pipeline counters — no credentials and no pairing keys, which stay in app-private storage and
 * are never part of a report.
 */
class DiagnosticServer(private val body: () -> String) {

    private var serverSocket: ServerSocket? = null
    private val threads = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FerryDiagnostics").apply { isDaemon = true }
    }
    @Volatile private var running = false
    @Volatile private var startedAtMs = 0L

    /** The URL to type on a phone, or null if the server is not up. */
    @Volatile var url: String? = null
        private set

    /** Starts the server. Idempotent; returns the URL, or null if it could not bind. */
    fun start(): String? {
        if (running) return url
        return runCatching {
            val socket = ServerSocket(0)
            serverSocket = socket
            running = true
            startedAtMs = System.currentTimeMillis()
            val address = localIpAddress() ?: "<tv-ip>"
            url = "http://$address:${socket.localPort}/"
            threads.execute { accept(socket) }
            Logger.i("Diagnostics available at $url")
            url
        }.onFailure { Logger.w("Diagnostics server could not start — ${it.message}") }.getOrNull()
    }

    fun stop() {
        running = false
        url = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Shut the executor down too, not just the socket. Without this the pool's core thread stays
        // alive for the life of the process, and a new server — a new thread — is created every time
        // the diagnostics screen is opened. Caught in a real startup log showing four opens in ninety
        // seconds; that is four threads that were never coming back, in the same class of leak 7.0.0
        // existed to fix. A diagnostic tool has no business being the thing that degrades the app.
        runCatching { threads.shutdown() }
    }

    private fun accept(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            if (System.currentTimeMillis() - startedAtMs > MAX_LIFETIME_MS) {
                Logger.i("Diagnostics server timed out — closing")
                stop()
                return
            }
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            runCatching { serve(client) }
                .onFailure { Logger.w("Diagnostics request failed — ${it.message}") }
            runCatching { client.close() }
        }
    }

    /**
     * Answers one request with the report as `text/plain`.
     *
     * Plain text on purpose: it is what someone wants to select, copy and paste into a message, and
     * a browser renders it without turning a stack trace into a wall of collapsed whitespace. The
     * request line is read and discarded — every path returns the same document, because there is
     * only one thing here to serve and pretending otherwise would just be surface to get wrong.
     */
    private fun serve(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        // Drain the request head so the client does not see a reset before it has finished writing.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val payload = runCatching { body() }.getOrElse { "diagnostics unavailable: ${it.message}" }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            write(bytes)
            flush()
        }
    }

    private companion object {
        /**
         * Hard ceiling on how long the socket stays open, whatever the UI does.
         *
         * Long enough to walk to another room and type a URL; short enough that a listening port
         * cannot be left open for the rest of the day by someone who wandered off mid-screen.
         */
        const val MAX_LIFETIME_MS = 15 * 60 * 1000L

        /** This device's LAN IPv4 address, for the URL. Null if it cannot be determined. */
        fun localIpAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
