package io.github.vagrant326.atvt9.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.ime.KeyBindings

/**
 * Captures one key from the remote.
 *
 * Not a list of key names, because a list cannot be right: remotes disagree about which keys
 * exist and about what they report, and the key this project most wanted turned out to be
 * keycode 300, outside the range any menu would have offered. The only reliable way to learn
 * what a button sends is to have the user press it.
 *
 * Reserved keys are refused with the reason shown. Silently ignoring them would look like the
 * remote not being heard, and the user would press harder.
 */
class KeyCaptureActivity : Activity() {

    private lateinit var captured: TextView
    private lateinit var save: Button
    private var keyCode = KeyBindings.NO_KEY

    private val binding: Binding by lazy {
        Binding.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_BINDING) }
            ?: Binding.TRIGGER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        captured = label(getString(R.string.capture_waiting), SECONDARY, 22f)
        save = action(getString(R.string.capture_save)) {
            Preferences(this).assign(binding, keyCode)
            finish()
        }.apply { isEnabled = false }

        val clear = action(getString(R.string.capture_clear)) {
            Preferences(this).assign(binding, KeyBindings.NO_KEY)
            finish()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(getString(binding.titleRes), Color.WHITE, 28f))
            addView(
                label(getString(binding.promptRes), SECONDARY, 15f)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
            addView(
                LinearLayout(this@KeyCaptureActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = card(CARD)
                    setPadding(dp(24), dp(22), dp(24), dp(24))
                    layoutParams = stack(dp(20))
                    addView(captured)
                }
            )
            addView(
                LinearLayout(this@KeyCaptureActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = stack(dp(12))
                    addView(save)
                    addView(clear)
                }
            )
            addView(
                label(getString(binding.fallbackRes), MUTED, 13f)
                    .apply { setPadding(0, dp(18), 0, 0) }
            )
            addView(
                label(getString(R.string.capture_back), MUTED, 13f)
                    .apply { setPadding(0, dp(10), 0, 0) }
            )
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(BACKGROUND)
                setPadding(dp(28), dp(32), dp(28), dp(32))
                addView(content)
            }
        )
    }

    /**
     * Every key is read here, including the d-pad, which is why the buttons cannot be reached
     * with it while capture is live. `BACK` therefore stays the way out and is never capturable
     * — a screen that can swallow its own exit is a screen that needs the TV unplugged.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode in KeyBindings.RESERVED) {
            captured.text = getString(R.string.capture_reserved, describe(keyCode))
            captured.setTextColor(WARNING)
            save.isEnabled = false
            return true
        }

        this.keyCode = keyCode
        captured.text = getString(R.string.capture_captured, describe(keyCode))
        captured.setTextColor(ACCENT)
        save.isEnabled = true
        save.requestFocus()
        return true
    }

    private fun describe(keyCode: Int): String {
        val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        return if (name.startsWith("UNKNOWN")) "$keyCode" else "$name ($keyCode)"
    }

    private fun label(text: String, colour: Int, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun action(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = card(SUNKEN)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(10) }
        setOnFocusChangeListener { view, hasFocus ->
            view.background = card(if (hasFocus) FOCUSED else SUNKEN)
        }
        setOnClickListener { onClick() }
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

    companion object {

        const val EXTRA_BINDING = "binding"

        private const val BACKGROUND = 0xFF08080B.toInt()
        private const val CARD = 0xFF16161C.toInt()
        private const val SUNKEN = 0xFF101014.toInt()
        private const val FOCUSED = 0xFF2A3A46.toInt()
        private const val ACCENT = 0xFF7FD1FF.toInt()
        private const val SECONDARY = 0xFFB0B0BC.toInt()
        private const val MUTED = 0xFF6B6B78.toInt()
        private const val WARNING = 0xFFE0A33C.toInt()
    }
}
