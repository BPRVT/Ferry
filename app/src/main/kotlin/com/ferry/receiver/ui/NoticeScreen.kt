package com.ferry.receiver.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ferry.receiver.R

/**
 * NoticeScreen — tells the viewer, on the television, that Ferry ended the session on purpose.
 *
 * ── Why this exists ──
 *
 * Ferry's recovery of last resort is to hang up on the sender, on the premise — stated in 6.7.0 and
 * carried unexamined ever since — that "the sender re-establishes it". **A log from hardware shows
 * that it does not.** The stream went silent, the watchdog correctly detected it and ended the
 * session, Ferry went back to advertising and waited, and the iPad never came back. From the sofa
 * that is indistinguishable from the app dying: the picture stops and nothing explains it. It was
 * reported, reasonably, as "a full freeze silent crash".
 *
 * The recovery is still the right action — the session really was dead. What was missing is that
 * nobody told the person watching. A frozen picture with no explanation is a bug report; the same
 * event with one line of text is a five-second fix the viewer can perform themselves.
 *
 * So this is not a cosmetic addition. It is the difference between a recovery that appears to be a
 * crash and one that appears to be a recovery.
 */
class NoticeScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val bodyView: TextView
    private val detailView: TextView

    init {
        setBackgroundColor(Color.parseColor("#F0101010"))
        isClickable = true
        isFocusable = true

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(SAFE_PX, SAFE_PX, SAFE_PX, SAFE_PX)
        }
        titleView = TextView(context).apply {
            setTextColor(Color.parseColor("#FFFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
        }
        bodyView = TextView(context).apply {
            setTextColor(Color.parseColor("#FFBBBBBB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        detailView = TextView(context).apply {
            setTextColor(Color.parseColor("#FF777777"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        column.addView(titleView)
        column.addView(bodyView)
        column.addView(detailView)
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * @param reason what the watchdog found, e.g. "session went silent" — shown small, because it is
     *   for reporting rather than for acting on. The instruction above it is what the viewer needs.
     */
    fun show(reason: String) {
        titleView.setText(R.string.notice_session_ended_title)
        bodyView.setText(R.string.notice_session_ended_body)
        detailView.text = context.getString(R.string.notice_session_ended_detail, reason)
    }

    private companion object {
        /** Android TV overscan-safe inset; a set may crop 5% of each edge. */
        const val SAFE_PX = 64
    }
}
