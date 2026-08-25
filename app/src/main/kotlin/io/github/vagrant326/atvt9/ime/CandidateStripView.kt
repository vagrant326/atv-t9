package io.github.vagrant326.atvt9.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.core.Candidate
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.settings.HintMode

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

    /**
     * The physical numpad, with the letters the remote does not carry.
     *
     * Ported from LetterWise, and it matters more here. There, a key press narrows a letter and
     * the strip shows the alternatives whatever happens. Here a press produces nothing visible
     * until the word resolves, so a user who does not know that `w` is on `9` has no way to find
     * out and no feedback telling them they guessed wrong — the grid is the only label this
     * hardware has.
     */
    private val keypad = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutParams = LayoutParams(dp(300), LayoutParams.WRAP_CONTENT)
    }

    private val keypadCells = mutableMapOf<Char, TextView>()

    private val spellValue = hintValue()
    private val languageValue = hintValue()
    private val deleteValue = hintValue()

    /**
     * The assigned keys, named rather than drawn into the grid, and set beside it. A key printed
     * `TEXT` does not sit where a phone has `*`, so putting it in a cell would lie about where
     * to reach for it. Beside, because the space right of a three-cell grid was going spare and
     * vertical space is what the results underneath are short of.
     */
    private val hints = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(20), 0, 0, 0)
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.TOP
            topMargin = dp(2)
        }
        addView(hintLine(context.getString(R.string.strip_hint_walk), hintValue().apply {
            text = context.getString(R.string.strip_walk_keys)
        }))
        addView(hintLine(context.getString(R.string.strip_hint_commit), hintValue().apply {
            text = context.getString(R.string.strip_commit_keys)
        }))
        addView(hintLine(context.getString(R.string.strip_hint_spell), spellValue))
        addView(hintLine(context.getString(R.string.strip_hint_delete), deleteValue))
        addView(hintLine(context.getString(R.string.strip_hint_language), languageValue))
    }

    /**
     * A weighted spacer mirroring [hints] keeps the grid centred while the hints sit to its
     * right. Without it the grid is pushed left by whatever is beside it.
     */
    private val hintRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
        addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        addView(keypad)
        addView(hints)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        buildKeypad()

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
        addView(hintRow)
    }

    /**
     * `123` / `456` / `789` / `0`. No `*` or `#` row — this is not a phone and those keys are
     * not on every remote, so drawing them promises buttons that may not exist.
     *
     * Built once. Unlike LetterWise, whose partition changes with the language, the E.161 layout
     * is fixed and Polish simply puts more letters on the same eight keys — so both languages
     * are shown at once and a switch changes nothing here.
     */
    private fun buildKeypad() {
        for (row in listOf("123", "456", "789", " 0 ")) {
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            for (key in row) {
                line.addView(cell(key))
            }
            keypad.addView(line)
        }
    }

    private fun cell(key: Char): TextView {
        val letters = when (key) {
            ' ' -> ""
            '0' -> context.getString(R.string.strip_space)
            '1' -> context.getString(R.string.strip_punctuation)
            else -> Keypad.lettersOn(key)
        }
        return TextView(context).apply {
            text = if (key == ' ') "" else "$key\n$letters"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.95f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
                topMargin = dp(3)
            }
            if (key != ' ') {
                setBackgroundColor(CELL)
            }
            keypadCells[key] = this
        }
    }

    /** Two columns, so the values line up instead of drifting with label length. */
    private fun hintLine(label: String, value: TextView) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(3) }
        addView(
            TextView(context).apply {
                text = label
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LayoutParams(dp(76), LayoutParams.WRAP_CONTENT)
            }
        )
        addView(value)
    }

    private fun hintValue() = TextView(context).apply {
        setTextColor(SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun keyLabel(keyCode: Int, fallback: String): String =
        if (keyCode == KeyBindings.NO_KEY) {
            fallback
        } else {
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
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
            // Says so rather than looking broken: raised by the trigger over an app that never
            // asked for input, there is nowhere to send characters.
            !state.hasEditor -> context.getString(R.string.strip_no_editor)
            !state.trained -> context.getString(R.string.strip_untrained)
            state.digits -> context.getString(R.string.strip_digits)

            // Spelling still types here — it is the only way to enter anything the dictionary
            // does not hold, which in a password or an address is everything. What changes is
            // that the keyboard stops saying the word will be kept, because it will not.
            state.spelling -> context.getString(
                if (state.learning) R.string.strip_spell else R.string.strip_spell_only
            )

            state.candidates.isEmpty() && state.sequence.isNotEmpty() -> context.getString(
                if (state.learning) R.string.strip_no_match else R.string.strip_no_match_only
            )

            else -> ""
        }
        status.text = message
        status.visibility = if (message.isEmpty()) GONE else VISIBLE

        // Digits are deterministic, so the grid has nothing to explain and the results
        // underneath get the room back.
        val gridVisible = state.hintMode == HintMode.KEYPAD && !state.digits && state.hasEditor
        hintRow.visibility = if (gridVisible) VISIBLE else GONE
        if (!gridVisible) {
            return
        }

        spellValue.text = keyLabel(
            state.customKeys.spell,
            context.getString(R.string.strip_fallback_spell),
        )
        deleteValue.text = keyLabel(
            state.customKeys.delete,
            context.getString(R.string.strip_fallback_delete),
        )
        languageValue.text = keyLabel(
            state.customKeys.language,
            context.getString(R.string.strip_fallback_language),
        )

        // Lights the keys pressed so far, in order, so the grid reads as part of what is
        // happening rather than as a static wall of text. On a method where a press produces
        // nothing visible until the word resolves, this is the only per-press feedback there is.
        val pressed = state.sequence.toSet()
        for ((key, cell) in keypadCells) {
            cell.setTextColor(if (key in pressed) ACCENT else MUTED)
        }
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
        const val CELL = 0xFF1A1A22.toInt()
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
    val hintMode: HintMode,
    val digits: Boolean,
    val hasEditor: Boolean,
    val learning: Boolean,
    val customKeys: CustomKeys,
)
