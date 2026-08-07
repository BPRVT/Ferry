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
    private val scroller: ScrollView

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
        scroller = ScrollView(context).apply { isFillViewport = true; addView(body) }
        addView(scroller)
    }

    /**
     * Scrolls the report by [pages] screenfuls, so the log below the fold can actually be read.
     *
     * A TV remote has no scroll wheel and this overlay swallows every key, so without an explicit
     * hook the content past the first screen is unreachable — which is exactly how the fetch URL
     * came to be invisible when it lived at the bottom of a 300-line report.
     */
    fun scrollByPages(pages: Int) {
        scroller.smoothScrollBy(0, (scroller.height * SCROLL_PAGE_FRACTION * pages).toInt())
    }

    /**
     * Renders [report] **underneath** a header carrying the fetch URL.
     *
     * The URL goes first, and that ordering is the whole lesson. It used to be a footer, appended
     * after the report — which is up to 300 log lines, on a screen where every key dismissed the
     * overlay and nothing scrolled it. So the one piece of information the feature exists to deliver
     * was reliably several screens below the fold and unreachable by design. Reported from hardware
     * against 7.3.0, with a photograph of a diagnostics screen showing no URL anywhere on it.
     *
     * @param fetchUrl LAN address served by [com.ferry.receiver.util.DiagnosticServer] while this
     *   screen is up, or null if it could not bind — in which case the photograph route is stated
     *   plainly rather than silently omitted.
     */
    fun show(report: String, fetchUrl: String?, live: Boolean) {
        body.text = buildString {
            appendLine(if (live) "Ferry diagnostics" else "Ferry crash report")
            if (fetchUrl != null) {
                appendLine()
                appendLine("Open this on your phone or laptop, same Wi-Fi:")
                appendLine()
                appendLine("    $fetchUrl")
                appendLine()
                appendLine(
                    if (live) "Reload it any time for the latest. Works while this screen is open."
                    else "Works while this screen is open."
                )
            } else {
                appendLine()
                appendLine("(No network address available - photograph this screen instead.)")
            }
            appendLine()
            appendLine("Up/Down to scroll.  Any other key to close.")
            appendLine("-".repeat(64))
            appendLine()
            appendLine(report.trimEnd())
        }
        scroller.scrollTo(0, 0)
    }

    private companion object {
        /** Android TV overscan-safe inset; a set may crop 5% of each edge. */
        const val SAFE_PX = 64

        /** How much of a screenful one Up/Down press moves, leaving context either side. */
        const val SCROLL_PAGE_FRACTION = 0.8f
    }
}
