package io.github.vagrant326.atvt9.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.core.Candidate

/**
 * The strip: what the presses so far could mean, best first, one of them chosen.
 *
 * This is the whole visible interface, and on this device it carries more weight than it does on
 * a phone. The remote has nothing printed on it, so the user cannot check which letters live on
 * which key by looking down — the strip is the only place the keyboard's state is legible at
 * all. That is also why the sequence is shown when nothing matches: on an ambiguous keypad,
 * "these letters are wrong" and "these letters do not exist" produce identical silence
 * otherwise, and they call for opposite actions.
 */
class CandidateStripView(context: Context) : LinearLayout(context) {

    private val words = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val status = TextView(context).apply {
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private val language = TextView(context).apply {
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(14), 0, 0, 0)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(dp(20), dp(12), dp(20), dp(12))

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(words)
                        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                )
                addView(language)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        )
        addView(status.apply { setPadding(0, dp(8), 0, 0) })
    }

    fun render(state: StripState) {
        language.text = state.language

        words.removeAllViews()
        when {
            state.spelling -> words.addView(chip(state.composing.ifEmpty { " " }, chosen = true))
            state.candidates.isEmpty() && state.sequence.isNotEmpty() ->
                words.addView(chip(state.sequence, chosen = true, unresolved = true))

            else -> state.candidates.forEachIndexed { index, candidate ->
                words.addView(chip(candidate.word, chosen = index == state.selected))
            }
        }

        val message = when {
            !state.trained -> context.getString(R.string.strip_untrained)
            state.spelling -> context.getString(R.string.strip_spell)
            state.candidates.isEmpty() && state.sequence.isNotEmpty() ->
                context.getString(R.string.strip_no_match)

            else -> ""
        }
        status.text = message
        status.visibility = if (message.isEmpty()) GONE else VISIBLE
    }

    /**
     * One word. The chosen one is filled rather than merely brighter: at three metres on a panel
     * of unknown calibration, a colour difference is not reliably a difference at all.
     */
    private fun chip(text: String, chosen: Boolean, unresolved: Boolean = false) =
        TextView(context).apply {
            this.text = text
            setTextColor(
                when {
                    unresolved -> WARNING
                    chosen -> Color.BLACK
                    else -> SECONDARY
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (chosen) 22f else 20f)
            setPadding(dp(14), dp(6), dp(14), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(if (chosen && !unresolved) ACCENT else Color.TRANSPARENT)
            }
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF08080B.toInt()
        const val ACCENT = 0xFF7FD1FF.toInt()
        const val SECONDARY = 0xFFB0B0BC.toInt()
        const val MUTED = 0xFF6B6B78.toInt()
        const val WARNING = 0xFFE0A33C.toInt()
    }
}

/** Everything the strip draws, so rendering has no opinion of its own about engine state. */
data class StripState(
    val candidates: List<Candidate>,
    val selected: Int,
    val sequence: String,
    val composing: String,
    val spelling: Boolean,
    val trained: Boolean,
    val language: String,
)
