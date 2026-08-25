package io.github.vagrant326.atvt9.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.model.UserWords
import java.io.File

/**
 * Everything the keyboard learnt, listed, with a way to remove any of it.
 *
 * This screen is the other half of the learning feature rather than a convenience on top of it.
 * A keyboard that silently accumulates what you type and offers no way to look at the pile is
 * indistinguishable from the thing `docs/00-overview.md` §3.1 spends a page promising this is
 * not — and the promise is only worth anything if it can be checked from the sofa.
 */
class WordsActivity : Activity() {

    private lateinit var words: UserWords
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        words = UserWords(File(filesDir, "words.bin"))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = stack(dp(20))
        }
        empty = label(getString(R.string.words_empty), MUTED, 15f).apply {
            layoutParams = stack(dp(20))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(getString(R.string.words_title), Color.WHITE, 28f))
            addView(
                label(getString(R.string.words_subtitle), SECONDARY, 15f)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
            // The rows are clickable and nothing else says so. On a TV there is no affordance
            // to discover by hovering, so an instruction that is never read is still cheaper
            // than a list that looks inert.
            addView(
                label(getString(R.string.words_remove), MUTED, 13f)
                    .apply { setPadding(0, dp(6), 0, 0) }
            )
            addView(empty)
            addView(list)
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    LinearLayout(this@WordsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(28), dp(32), dp(28), dp(32))
                        addView(content)
                    }
                )
            }
        )

        render()
    }

    override fun onPause() {
        words.flush()
        super.onPause()
    }

    private fun render() {
        list.removeAllViews()
        val stored = words.dictionary.words()
        empty.visibility = if (stored.isEmpty()) View.VISIBLE else View.GONE

        for (word in stored) {
            list.addView(row(word, words.dictionary.usesOf(word)))
        }
    }

    private fun row(word: String, uses: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = card(SUNKEN)
        setPadding(dp(20), dp(12), dp(20), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        isFocusable = true

        addView(label(word, Color.WHITE, 19f))
        addView(
            label(getString(R.string.words_uses, uses), MUTED, 13f)
                .apply { setPadding(0, dp(3), 0, 0) }
        )

        setOnFocusChangeListener { view, hasFocus ->
            view.background = card(if (hasFocus) FOCUSED else SUNKEN)
        }
        setOnClickListener {
            words.dictionary.forget(word)
            words.flush()
            render()
        }
    }

    private fun label(text: String, colour: Int, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun card(colour: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(colour)
    }

    private fun stack(topMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF08080B.toInt()
        const val SUNKEN = 0xFF101014.toInt()
        const val FOCUSED = 0xFF2A3A46.toInt()
        const val SECONDARY = 0xFFB0B0BC.toInt()
        const val MUTED = 0xFF6B6B78.toInt()
    }
}
