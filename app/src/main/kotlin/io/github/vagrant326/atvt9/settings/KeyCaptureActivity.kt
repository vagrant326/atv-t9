package io.github.vagrant326.atvt9.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.ime.KeyBindings

/**
 * Puts a keyboard function on a button of the user's choosing.
 *
 * This exists because remotes disagree about what physically exists and about what it reports.
 * On this remote the key sitting where a phone has `*` is printed `TEXT` and sends a keycode
 * outside the standard range. Nothing in the app can discover that; only the user pressing the
 * button can, and guessing produced a function that could not be reached at all — worse than
 * an absent one, because it looks implemented.
 *
 * Listening for a raw key is a brief state rather than the screen's permanent mode, and that is
 * what lets ordinary focusable buttons live here. An earlier version listened all the time and
 * the d-pad never reached the buttons, so trying to move between them looked like it was
 * reassigning the key.
 *
 * Driven entirely by [Binding], so putting another function on a user-chosen button costs one
 * enum entry and nothing here.
 */
class KeyCaptureActivity : Activity() {

    private lateinit var preferences: Preferences
    private lateinit var binding: Binding

    private lateinit var stateLabel: TextView
    private lateinit var keyName: TextView
    private lateinit var keyDetail: TextView
    private lateinit var note: TextView
    private lateinit var footer: TextView
    private lateinit var assignedValue: TextView
    private lateinit var chooseButton: Button
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button
    private lateinit var targetCard: LinearLayout

    private var listening = false
    private var candidate: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Preferences(this)
        binding = Binding.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_BINDING) }
            ?: Binding.TRIGGER

        stateLabel = label(getString(R.string.capture_state_none), MUTED, 12f)
        keyName = label(getString(R.string.capture_no_key), Color.WHITE, 30f)
        keyDetail = label("", SECONDARY, 15f)
        note = label("", WARNING, 14f).apply { visibility = View.GONE }
        footer = label(getString(R.string.capture_footer_idle), MUTED, 13f)
        assignedValue = label(assignedText(), SECONDARY, 15f)

        targetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(CARD)
            setPadding(dp(24), dp(22), dp(24), dp(24))
            layoutParams = stack(dp(12))
            addView(stateLabel)
            addView(keyName.apply { setPadding(0, dp(6), 0, 0) })
            addView(keyDetail.apply { setPadding(0, dp(4), 0, 0) })
            addView(note.apply { setPadding(0, dp(12), 0, 0) })
        }

        // Choose sits above the code it produces, save and clear below it: the order the user
        // moves through, rather than three buttons in a row with no relation to the reading.
        chooseButton = action(getString(R.string.capture_choose)) { startListening() }.apply {
            layoutParams = stack(dp(20))
        }
        saveButton = action(getString(R.string.capture_save)) { save() }
        clearButton = action(getString(R.string.capture_clear)) { clear() }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = stack(dp(12))
            addView(saveButton)
            addView(clearButton)
        }

        val assigned = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(SUNKEN)
            setPadding(dp(24), dp(16), dp(24), dp(18))
            layoutParams = stack(dp(16))
            addView(label(getString(R.string.capture_assigned_label), MUTED, 12f))
            addView(assignedValue.apply { setPadding(0, dp(4), 0, 0) })
            addView(
                label(getString(binding.fallbackRes), MUTED, 13f)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label(getString(binding.titleRes), Color.WHITE, 28f))
            addView(
                label(getString(binding.promptRes), SECONDARY, 15f)
                    .apply { setPadding(0, dp(6), 0, 0) }
            )
            addView(chooseButton)
            addView(targetCard)
            addView(actions)
            addView(assigned)
            addView(footer.apply { setPadding(0, dp(18), 0, 0) })
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    LinearLayout(this@KeyCaptureActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(28), dp(28), dp(28), dp(32))
                        addView(content)
                    }
                )
            }
        )

        updateActions()
        chooseButton.requestFocus()
    }

    private fun startListening() {
        listening = true
        candidate = null
        targetCard.background = card(CARD_LISTENING)
        stateLabel.text = getString(R.string.capture_state_listening)
        keyName.text = getString(R.string.capture_waiting)
        keyDetail.text = ""
        note.visibility = View.GONE
        footer.text = getString(R.string.capture_footer_listening)
        updateActions()
    }

    private fun stopListening() {
        listening = false
        targetCard.background = card(CARD)
        footer.text = getString(R.string.capture_footer_idle)
        updateActions()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Outside the listening state the keys belong to focus navigation and the buttons.
        if (!listening) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                finish()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (event.repeatCount > 0) {
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            stateLabel.text = getString(R.string.capture_state_none)
            keyName.text = getString(R.string.capture_no_key)
            stopListening()
            return true
        }

        // Exactly one press is taken, whether it turns out to be usable or not. Showing the
        // refused key is the point: it answers "did it even see my button".
        candidate = keyCode
        keyName.text = KeyEvent.keyCodeToString(keyCode)
        keyDetail.text = getString(R.string.capture_code, keyCode)

        if (keyCode in KeyBindings.RESERVED) {
            stateLabel.text = getString(R.string.capture_state_unusable)
            note.text = getString(R.string.capture_reserved)
            note.visibility = View.VISIBLE
        } else {
            stateLabel.text = getString(R.string.capture_state_selected)
            note.visibility = View.GONE
        }
        stopListening()
        return true
    }

    private fun save() {
        val code = candidate ?: return
        preferences.assign(binding, code)
        assignedValue.text = assignedText()
        stateLabel.text = getString(R.string.capture_state_saved)
        footer.text = getString(R.string.capture_footer_saved)
    }

    private fun clear() {
        preferences.assign(binding, KeyBindings.NO_KEY)
        candidate = null
        assignedValue.text = assignedText()
        stateLabel.text = getString(R.string.capture_state_none)
        keyName.text = getString(R.string.capture_no_key)
        keyDetail.text = ""
        note.visibility = View.GONE
        updateActions()
    }

    private fun updateActions() {
        val selected = candidate
        enable(saveButton, !listening && selected != null && selected !in KeyBindings.RESERVED)
        enable(clearButton, !listening && preferences.keyCodeFor(binding) != KeyBindings.NO_KEY)
        enable(chooseButton, !listening)
    }

    private fun enable(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.isFocusable = enabled
        button.setTextColor(if (enabled) Color.WHITE else MUTED)
    }

    private fun assignedText(): String {
        val code = preferences.keyCodeFor(binding)
        return if (code == KeyBindings.NO_KEY) {
            getString(R.string.capture_assigned_none)
        } else {
            getString(R.string.capture_assigned_value, KeyEvent.keyCodeToString(code), code)
        }
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
        private const val CARD_LISTENING = 0xFF23303A.toInt()
        private const val SUNKEN = 0xFF101014.toInt()
        private const val FOCUSED = 0xFF2A3A46.toInt()
        private const val SECONDARY = 0xFFB0B0BC.toInt()
        private const val MUTED = 0xFF6B6B78.toInt()
        private const val WARNING = 0xFFEF9F27.toInt()
    }
}
