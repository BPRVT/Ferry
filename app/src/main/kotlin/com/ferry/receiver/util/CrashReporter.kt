package com.ferry.receiver.util

import android.content.Context
import com.ferry.receiver.airplay.StreamStats
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashReporter — records why Ferry died, so the next launch can say what happened.
 *
 * ── Why this is not optional here ──
 *
 * Ferry runs on a TV stick with no adb access, and the only person who sees it fail cannot read
 * logcat. A crash therefore produces exactly one piece of evidence: "it froze and then it crashed."
 * That is not enough to fix anything, and every fix attempted without it is a guess — which is how
 * this project has previously spent three releases aiming at the wrong half of a pipeline.
 *
 * So the device has to keep the evidence itself. This writes a report to app-private storage the
 * moment the process dies, and [read] hands it back on the next launch for the UI to put on screen.
 *
 * ── What goes in a report ──
 *
 * The stack trace, obviously. But also a snapshot of [StreamStats] — what the video and audio
 * pipeline were doing at that instant — because the stack says *where* it died and the counters say
 * *what it was in the middle of*, and for this codebase the second has consistently been the more
 * useful of the two. A crash in the decoder with `q 16/16` and a stale `last` is a completely
 * different bug from the same crash with `q 1/16`.
 *
 * ── Deliberately not a crash-reporting service ──
 *
 * Nothing is uploaded anywhere. The file is app-private, it holds no personal data beyond a sender
 * name that Ferry already displays on screen, and it is overwritten by the next report. Ferry asks
 * for no network permission beyond the LAN it mirrors over, and that stays true.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    /** Where a report goes once it has been shown — kept, not deleted. See [markSeen]. */
    private const val ARCHIVE_NAME = "last-crash-seen.txt"

    /** Cap on a stored report, so a pathological recursive stack cannot fill a stick's storage. */
    private const val MAX_REPORT_BYTES = 64 * 1024

    @Volatile private var appContext: Context? = null

    /**
     * Installs the process-wide handler. Call once, from `Application.onCreate`.
     *
     * The previous handler is kept and always invoked afterwards: Android's default is what actually
     * terminates the process and tells the system the app died. Replacing it rather than chaining to
     * it would leave a dead process sitting there, which is a worse failure than the crash.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Wrapped, because a handler that throws replaces a diagnosable crash with an
            // undiagnosable one — and this runs at the least recoverable moment in the process.
            runCatching { write(describe("CRASH", "thread ${thread.name}", stackOf(error))) }
            previous?.uncaughtException(thread, error)
        }
        Logger.i("Crash reporter installed")
    }

    /**
     * Records a freeze detected by [MainThreadWatchdog] — a stall, not a throw.
     *
     * Stored through the same channel as a crash because from the couch they are the same event:
     * the picture stops and the app stops responding. The distinction that matters is in the report,
     * where a FREEZE names the thread that was blocked and what it was blocked in.
     */
    fun recordFreeze(blockedForMs: Long, stack: Array<StackTraceElement>) {
        runCatching {
            write(describe("FREEZE", "main thread blocked for ${blockedForMs}ms", stack.joinToString("\n") { "\tat $it" }))
        }
    }

    /**
     * A report for right now: what the pipeline is doing, the recent log, and the last stored crash
     * if there was one.
     *
     * The counterpart to [read], and the more useful of the two in practice. Waiting for a crash to
     * find out what Ferry is doing is backwards — most of what goes wrong here does not throw. It
     * stutters, or drops frames, or advertises a resolution the sender declines, and all of that is
     * visible in the log and the counters while it is happening and gone afterwards.
     *
     * Rebuilt on every call rather than snapshotted, so reloading the page in a browser shows the
     * current state instead of whatever it was when the screen was opened.
     */
    fun liveReport(): String = buildString {
        appendLine("Ferry diagnostics")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine()
        appendLine(runCatching { StreamStats.summary() }.getOrElse { "(stats unavailable)" })
        appendLine()
        lastReport()?.let {
            appendLine("─── last recorded crash or freeze ───")
            appendLine(it.trimEnd())
            appendLine()
        }
        appendLine("─── recent log ───")
        appendLine(runCatching { LogRing.snapshot() }.getOrElse { "(log unavailable)" })
    }

    /** The last stored report, or null if Ferry's previous run ended cleanly. */
    fun read(): String? = runCatching {
        val file = file() ?: return null
        if (!file.exists()) null else file.readText().ifBlank { null }
    }.getOrNull()

    /**
     * Marks the stored report as seen — **without destroying it**.
     *
     * This used to delete the file, and that was the wrong call in the most consequential way
     * available. The entire purpose of a crash report here is to be *sent to someone*, and glancing
     * at it on a television is not the same as having got it off the device. Deleting on dismiss
     * meant a single keypress — including the accidental one that closes an overlay you did not
     * expect — permanently destroyed the only copy of evidence for a failure that may take days to
     * reproduce. Reported from hardware: a real crash whose report was gone before it could be read.
     *
     * So the report is moved aside instead. It stops appearing unprompted at launch, which is all
     * "seen" ever needed to mean, and stays available under Settings → Diagnostics until a *newer*
     * crash replaces it. The only thing that discards one now is another one.
     */
    fun markSeen() {
        runCatching {
            val current = file() ?: return
            val archive = archiveFile() ?: return
            if (!current.exists()) return
            archive.delete()
            if (!current.renameTo(archive)) {
                // Rename can fail; copying still preserves the evidence, which is the point.
                archive.writeText(current.readText())
                current.delete()
            }
        }.onFailure { Logger.w("Could not archive the crash report — ${it.message}") }
    }

    /** The most recent report, seen or not — what Settings → Diagnostics shows. */
    fun lastReport(): String? = read() ?: runCatching {
        archiveFile()?.takeIf { it.exists() }?.readText()?.ifBlank { null }
    }.getOrNull()

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    private fun archiveFile(): File? = appContext?.let { File(it.filesDir, ARCHIVE_NAME) }

    private fun write(report: String) {
        val target = file() ?: return
        target.writeText(report.take(MAX_REPORT_BYTES))
    }

    /**
     * Builds the report body. Kept to what fits on a TV screen and reads without tooling — this is
     * meant to be photographed and read, not parsed.
     */
    private fun describe(kind: String, where: String, detail: String): String = buildString {
        appendLine("Ferry $kind")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        appendLine(where)
        appendLine()
        // The pipeline's state at the moment of death. See the KDoc: this is usually the half of the
        // report that identifies the bug, because it says what Ferry was in the middle of doing.
        appendLine(runCatching { StreamStats.summary() }.getOrElse { "(stats unavailable)" })
        appendLine()
        appendLine(detail)
        appendLine()
        // What led up to it. The stack names the instruction that failed; these name the sequence,
        // and in this codebase the sequence has repeatedly been the half that identified the bug.
        appendLine("─── recent log ───")
        appendLine(runCatching { LogRing.snapshot() }.getOrElse { "(log unavailable)" })
    }

    private fun stackOf(error: Throwable): String = StringWriter().also { sw ->
        PrintWriter(sw).use { error.printStackTrace(it) }
    }.toString()
}
