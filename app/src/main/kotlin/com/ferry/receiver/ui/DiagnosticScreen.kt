package com.ferry.receiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * DiagnosticScreen — shows why Ferry died last time, on the only display anyone can read.
 *
 * Ferry runs on a TV stick with no adb, so a crash or a freeze normally leaves evidence in exactly
 * two places the user cannot reach: logcat, and `/data/anr/`. `CrashReporter` writes the report to
 * app-private storage instead, and this puts it on the television at the next launch, where it can
 * be read — or, more usefully, photographed and sent on.
 *
 * Monospaced and scrollable because the payload is a stack trace, and deliberately dismissed by any
 * key: it appears in front of someone who wanted to cast, and must never be something to fight past.
 */
class DiagnosticScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val body: TextView

    init {
        setBackgroundColor(Color.parseColor("#F2000000"))
        // Clickable/focusable so the overlay swallows input meant for whatever is behind it.
        isClickable = true
        isFocusable = true

        body = TextView(context).apply {
            setTextColor(Color.parseColor("#FFFF6B6B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(SAFE_PX, SAFE_PX, SAFE_PX, SAFE_PX)
            setTextIsSelectable(false)
        }
        addView(ScrollView(context).apply { addView(body) })
    }

    /**
     * Renders [report], with a footer telling the user how to get it off the TV.
     *
     * [fetchUrl] is a LAN address served by [com.ferry.receiver.util.DiagnosticServer] while this
     * screen is up. It is the point of the whole feature: a stack trace photographed off a
     * television is transcribable in theory and miserable in practice, and this makes the same text
     * selectable in a phone browser. Null when the server could not bind, in which case the
     * photograph route is still there and is said so plainly rather than silently omitted.
     */
    fun show(report: String, fetchUrl: String?) {
        body.text = buildString {
            appendLine(report.trimEnd())
            appendLine()
            appendLine("─".repeat(60))
            appendLine("Ferry recorded this after it stopped responding.")
            appendLine()
            if (fetchUrl != null) {
                appendLine("To copy it as text, open this on your phone or laptop")
                appendLine("(same Wi-Fi, no app needed):")
                appendLine()
                appendLine("    $fetchUrl")
                appendLine()
                appendLine("The address works while this screen is open.")
            } else {
                appendLine("Photograph this screen to report the problem.")
            }
            appendLine("Press any key to dismiss — it will not be shown again.")
        }
    }

    private companion object {
        /** Android TV overscan-safe inset; a set may crop 5% of each edge. */
        const val SAFE_PX = 64
    }
}
