package com.ferry.receiver.util

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicLong

/**
 * MainThreadWatchdog — catches a freeze while it is still happening, and names what caused it.
 *
 * ── The failure this exists for ──
 *
 * "It froze completely and then crashed" is the signature of an **ANR**: the main thread stops
 * servicing its message queue, the UI stops responding, and after a few seconds the system kills the
 * app. Android does write an ANR trace for this, into `/data/anr/`, which on a Fire TV stick with no
 * adb is a file nobody can ever read. So the event that matters most is the one that leaves the
 * least evidence.
 *
 * The mechanism is deliberately simple, because a freeze detector that can itself be blocked by the
 * freeze is worthless: a dedicated background thread posts a heartbeat to the main Looper and waits
 * for it to come back. If it does not come back within [STALL_THRESHOLD_MS], the main thread is by
 * definition stuck, and this captures **its** stack trace — which is the one piece of information
 * that turns "Ferry froze" into "Ferry froze in this exact call".
 *
 * ── Why the stack of the *main* thread, not this one ──
 *
 * This thread is fine; it is the one doing the observing. The interesting question is what the main
 * thread was doing when it stopped answering, and Java lets us ask any live thread for its stack.
 * That is the whole trick, and it is why this can diagnose a hang that no log line would.
 *
 * The report goes to [CrashReporter], so it survives the process being killed and is on screen at
 * the next launch — the only channel that reaches the person who saw the freeze.
 */
class MainThreadWatchdog(private val onStall: (Long, Array<StackTraceElement>) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by the main thread each time the heartbeat runs; read by the watchdog thread. */
    private val lastBeatMs = AtomicLong(0)

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        lastBeatMs.set(System.currentTimeMillis())
        thread = Thread({
            // Below default priority on purpose. This must never compete with the work it is
            // watching — a watchdog that steals time from the main thread would cause the very
            // stalls it reports.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            loop()
        }, "FerryHealth").apply { isDaemon = true; start() }
        Logger.i("Main-thread watchdog started (stall threshold ${STALL_THRESHOLD_MS}ms)")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        // True while we are inside one stall episode, so a five-second freeze produces one report
        // rather than one per tick. Reset when the main thread starts answering again.
        var reported = false
        while (running) {
            val sentAtMs = System.currentTimeMillis()
            // If the main thread is already wedged, this simply sits in the queue behind whatever is
            // blocking it — which is exactly the signal we are looking for.
            mainHandler.post { lastBeatMs.set(System.currentTimeMillis()) }

            try { Thread.sleep(CHECK_INTERVAL_MS) } catch (_: InterruptedException) { return }
            if (!running) return

            val stalledMs = System.currentTimeMillis() - maxOf(lastBeatMs.get(), 0L)
            if (stalledMs < STALL_THRESHOLD_MS) {
                if (reported) {
                    Logger.w("Main thread recovered after a stall")
                    reported = false
                }
                continue
            }
            if (reported) continue

            reported = true
            // Sampled here, while the thread is still stuck. A moment later it may have moved on,
            // and a stack captured after recovery describes nothing.
            val stack = runCatching { Looper.getMainLooper().thread.stackTrace }
                .getOrElse { emptyArray() }
            Logger.e("Main thread has not responded for ${stalledMs}ms — capturing its stack")
            runCatching { onStall(stalledMs, stack) }

            // Deliberately does NOT kill the app or try to "recover". The main thread is blocked in
            // something, and there is no safe way to unblock another thread from here — Thread.stop
            // and friends corrupt whatever they interrupt. Recording it and letting the system's own
            // ANR handling proceed is the honest response; the point of this class is evidence, not
            // intervention.
            if (System.currentTimeMillis() - sentAtMs > STALL_THRESHOLD_MS * 4) {
                Logger.e("Main thread still blocked well past the ANR threshold")
            }
        }
    }

    private companion object {
        /** How often the heartbeat is posted. Cheap: one Runnable on an otherwise idle queue. */
        const val CHECK_INTERVAL_MS = 1_000L

        /**
         * How long the main thread may fail to answer before it counts as frozen.
         *
         * Android's own ANR threshold for input is 5 s, so this sits just under it: the aim is to
         * capture the stack *before* the system decides to kill the app, not afterwards. Long enough
         * that ordinary main-thread work — inflating the settings screen, a layout pass on a slow
         * SoC — never trips it.
         */
        const val STALL_THRESHOLD_MS = 4_000L
    }
}
