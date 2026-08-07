package com.ferry.receiver.util

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * LogRing — keeps the last few hundred log lines in memory, so a crash report can say what led up
 * to the crash rather than only where it landed.
 *
 * ── Why in memory rather than a log file ──
 *
 * A stack trace names the instruction that failed. It does not say that the decoder had been rebuilt
 * four times in the preceding ten seconds, or that the mirror data connection dropped just before —
 * and in this codebase the sequence has consistently been the part that identified the bug, while
 * the stack alone supported two or three equally plausible stories. [CrashReporter] therefore folds
 * these lines into every report.
 *
 * A ring buffer rather than a growing file because Ferry runs on a stick with very little free
 * storage, and an unbounded log is a slow way to fill it. Nothing here is ever written to disk on
 * its own — the lines exist only in RAM until a crash or freeze copies the current contents into a
 * report.
 *
 * ── Why this is safe to plant in release ──
 *
 * [FerryApp] deliberately plants no Timber tree in release builds, so that debug detail never
 * reaches logcat where any app on the device could read it. This tree keeps that property: it does
 * not call `android.util.Log` at all. The lines stay inside Ferry's own process and leave it only if
 * the user chooses to show or fetch a report.
 *
 * It also stays at [MIN_PRIORITY] and above, which keeps the buffer to lifecycle events, warnings
 * and errors. That is a signal-to-noise decision, not a privacy one: at 60 fps a verbose line per
 * frame would flush everything interesting out of the ring in under three seconds.
 */
object LogRing {

    private const val CAPACITY = 300
    private const val MIN_PRIORITY = Log.INFO

    private val lines = ArrayDeque<String>(CAPACITY)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Plants the collector. Safe to call once, from `Application.onCreate`. */
    fun install() {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority < MIN_PRIORITY) return
                add(priority, tag, message, t)
            }
        })
    }

    private fun add(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "?"
        }
        val time = synchronized(stamp) { stamp.format(Date()) }
        val text = buildString {
            append(time).append(' ').append(level).append(' ')
            if (!tag.isNullOrEmpty()) append(tag).append(": ")
            append(message)
            // Only the exception's own line, not its stack: the stack of the throwable that *killed*
            // the process is already in the report in full, and the ones logged along the way are
            // there to show that they happened, not to be read frame by frame.
            t?.let { append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message) }
        }
        synchronized(lines) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(text)
        }
    }

    /** A snapshot of the buffer, oldest first, as one block of text. */
    fun snapshot(): String = synchronized(lines) {
        if (lines.isEmpty()) "(no log lines captured)" else lines.joinToString("\n")
    }
}
