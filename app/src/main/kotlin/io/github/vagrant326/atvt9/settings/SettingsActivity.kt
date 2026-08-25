package io.github.vagrant326.atvt9.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.vagrant326.atvt9.BuildConfig
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.model.DictionaryRepository
import io.github.vagrant326.atvt9.model.Language
import io.github.vagrant326.atvt9.model.UserWords
import io.github.vagrant326.atvt9.update.UpdateActivity
import java.io.File

/**
 * The whole of the app outside the keyboard itself.
 *
 * Built in code rather than XML for the same reason as the strip: every screen here is a stack
 * of rows that the d-pad walks top to bottom, and a layout file would describe that at greater
 * length without describing it better.
 */
class SettingsActivity : Activity() {

    private lateinit var preferences: Preferences
    private lateinit var dictionaries: DictionaryRepository
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = Preferences(this)
        dictionaries = DictionaryRepository(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(BACKGROUND)
                addView(
                    LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(28), dp(32), dp(28), dp(40))
                        addView(content)
                    }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()

        content.addView(label(getString(R.string.settings_title), Color.WHITE, 30f))
        content.addView(
            label(getString(R.string.settings_subtitle), SECONDARY, 15f)
                .apply { setPadding(0, dp(8), 0, dp(4)) }
        )

        // An IME the user has not enabled in Android's own settings does nothing at all, and
        // nothing in this app can enable it — so the first thing the screen does is say so, and
        // only while it is true.
        if (!isEnabledInSystem()) {
            content.addView(
                row(
                    getString(R.string.settings_enable),
                    getString(R.string.settings_open_keyboards),
                    accent = true,
                ) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            )
        }

        content.addView(heading(getString(R.string.settings_languages)))
        content.addView(caption(getString(R.string.settings_languages_body)))
        for (language in Language.entries) {
            val installed = dictionaries.wordCount(language)
            val detail = if (installed == 0) {
                getString(R.string.settings_language_missing)
            } else {
                getString(R.string.settings_language_words, installed)
            }
            content.addView(
                row(
                    getString(language.titleRes),
                    detail,
                    checked = preferences.isEnabled(language),
                ) {
                    if (!preferences.toggle(language)) {
                        Toast.makeText(
                            this,
                            R.string.settings_last_language,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    render()
                }
            )
        }

        content.addView(heading(getString(R.string.settings_keys)))
        content.addView(caption(getString(R.string.settings_keys_body)))
        for (binding in Binding.entries) {
            val assigned = preferences.keyCodeFor(binding)
            val detail = if (assigned == KeyBindingsLabel.NONE) {
                getString(R.string.settings_unassigned)
            } else {
                KeyBindingsLabel.describe(assigned)
            }
            content.addView(row(getString(binding.titleRes), detail) {
                startActivity(
                    Intent(this, KeyCaptureActivity::class.java)
                        .putExtra(KeyCaptureActivity.EXTRA_BINDING, binding.name)
                )
            })
        }

        val learnt = UserWords(File(filesDir, "words.bin")).dictionary.size
        content.addView(heading(getString(R.string.settings_words)))
        content.addView(
            row(
                getString(R.string.words_title),
                if (learnt == 0) {
                    getString(R.string.settings_words_none)
                } else {
                    getString(R.string.settings_words_body, learnt)
                },
            ) {
                startActivity(Intent(this, WordsActivity::class.java))
            }
        )

        content.addView(heading(getString(R.string.settings_updates)))
        content.addView(
            row(
                getString(R.string.update_title),
                getString(R.string.settings_updates_body, BuildConfig.VERSION_NAME),
            ) {
                startActivity(Intent(this, UpdateActivity::class.java))
            }
        )
    }

    private fun isEnabledInSystem(): Boolean {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return true
        return manager.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun heading(text: String) = label(text, Color.WHITE, 20f).apply {
        setPadding(0, dp(28), 0, 0)
    }

    private fun caption(text: String) = label(text, MUTED, 13f).apply {
        setPadding(0, dp(6), 0, 0)
    }

    private fun row(
        title: String,
        detail: String,
        checked: Boolean? = null,
        accent: Boolean = false,
        onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val resting = if (accent) CARD_ACCENT else SUNKEN
        background = card(resting)
        setPadding(dp(20), dp(14), dp(20), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
        isFocusable = true

        val mark = when (checked) {
            true -> "● "
            false -> "○ "
            null -> ""
        }
        addView(label(mark + title, Color.WHITE, 18f))
        addView(
            label(detail, if (accent) SECONDARY else MUTED, 13f)
                .apply { setPadding(0, dp(4), 0, 0) }
        )

        setOnFocusChangeListener { view, hasFocus ->
            view.background = card(if (hasFocus) FOCUSED else resting)
        }
        setOnClickListener { onClick() }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF08080B.toInt()
        const val CARD_ACCENT = 0xFF14283A.toInt()
        const val SUNKEN = 0xFF101014.toInt()
        const val FOCUSED = 0xFF2A3A46.toInt()
        const val SECONDARY = 0xFFB0B0BC.toInt()
        const val MUTED = 0xFF6B6B78.toInt()
    }
}

/** Naming a captured keycode for the settings list, which is the only place it is ever shown. */
private object KeyBindingsLabel {

    const val NONE = 0

    fun describe(keyCode: Int): String {
        val name = android.view.KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        return if (name.startsWith("UNKNOWN")) "$keyCode" else "$name ($keyCode)"
    }
}
