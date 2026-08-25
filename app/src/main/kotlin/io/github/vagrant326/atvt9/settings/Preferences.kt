package io.github.vagrant326.atvt9.settings

import android.content.Context
import androidx.annotation.StringRes
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.ime.CustomKeys
import io.github.vagrant326.atvt9.ime.KeyBindings
import io.github.vagrant326.atvt9.model.Language

/**
 * How much of the key mapping the strip spells out.
 *
 * [KEYPAD] is the default, and on this hardware that is not a preference. **Nothing is printed
 * on the remote** — the number keys carry no letters — so without the grid the user is pressing
 * unlabelled buttons and guessing which one holds `w`. Every phone this method came from had the
 * letters moulded into the keys; a TV remote is the first place T9 has ever run where the
 * mapping is invisible, and the strip is the only surface that can carry it.
 *
 * [STRIP] is the grid turned off once the mapping is in the thumb, keeping the candidates and
 * the state line. [OFF] gives the results underneath the most room and assumes everything.
 */
enum class HintMode(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    KEYPAD(R.string.hint_keypad, R.string.hint_keypad_description),
    STRIP(R.string.hint_strip, R.string.hint_strip_description),
    OFF(R.string.hint_off, R.string.hint_off_description),
    ;

    fun next(): HintMode = entries[(ordinal + 1) % entries.size]
}

/**
 * A function the user can put on a button of their choosing.
 *
 * All five are conveniences except the trigger, which has to be a real key because the keyboard
 * is not on screen when it is needed.
 */
enum class Binding(
    @StringRes val titleRes: Int,
    @StringRes val promptRes: Int,
    @StringRes val fallbackRes: Int,
) {
    /**
     * The only binding the keyboard listens for while it is hidden, which is the mechanism that
     * once left a TV unnavigable — so it is one key, chosen by the user, and unassigned by
     * default. Reserved keys cannot be picked, so the d-pad and the number keys are never at
     * risk.
     */
    TRIGGER(
        R.string.binding_trigger,
        R.string.binding_trigger_prompt,
        R.string.binding_trigger_fallback,
    ),
    SPELL(
        R.string.binding_spell,
        R.string.binding_spell_prompt,
        R.string.binding_spell_fallback,
    ),
    DELETE(
        R.string.binding_delete,
        R.string.binding_delete_prompt,
        R.string.binding_delete_fallback,
    ),
    LANGUAGE(
        R.string.binding_language,
        R.string.binding_language_prompt,
        R.string.binding_language_fallback,
    ),

    /** Optional twice over: a numeric field turns the digit mode on by itself. */
    DIGITS(
        R.string.binding_digits,
        R.string.binding_digits_prompt,
        R.string.binding_digits_fallback,
    ),
}

class Preferences(context: Context) {

    private val store = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Which languages the language switch cycles through. Never empty: a keyboard with no
     * language has no dictionary and is a multitap keyboard with extra steps.
     */
    var enabledLanguages: List<Language>
        get() {
            val stored = store.getStringSet(KEY_ENABLED_LANGUAGES, null)
                ?: return listOf(Language.PL, Language.EN)
            val enabled = Language.entries.filter { it.name in stored }
            return enabled.ifEmpty { listOf(Language.entries.first()) }
        }
        set(value) {
            val kept = value.ifEmpty { listOf(Language.entries.first()) }
            store.edit().putStringSet(KEY_ENABLED_LANGUAGES, kept.map { it.name }.toSet()).apply()
            if (activeLanguage !in kept) {
                activeLanguage = kept.first()
            }
        }

    fun isEnabled(language: Language): Boolean = language in enabledLanguages

    /** Refuses to remove the last language, and says so, rather than looking broken. */
    fun toggle(language: Language): Boolean {
        val current = enabledLanguages
        if (language in current) {
            if (current.size == 1) {
                return false
            }
            enabledLanguages = current - language
        } else {
            enabledLanguages = current + language
        }
        return true
    }

    /** Survives restarts: the language is a mode, and a mode that silently resets is a trap. */
    var activeLanguage: Language
        get() = store.getString(KEY_ACTIVE_LANGUAGE, null)
            ?.let { stored -> Language.entries.firstOrNull { it.name == stored } }
            ?.takeIf { it in enabledLanguages }
            ?: enabledLanguages.first()
        set(value) = store.edit().putString(KEY_ACTIVE_LANGUAGE, value.name).apply()

    /** Unassigned by default: nothing is consumed while the keyboard is hidden until asked. */
    var triggerKeyCode: Int
        get() = store.getInt(KEY_TRIGGER_KEYCODE, KeyBindings.NO_KEY)
        set(value) = store.edit().putInt(KEY_TRIGGER_KEYCODE, value).apply()

    var spellKeyCode: Int
        get() = store.getInt(KEY_SPELL_KEYCODE, KeyBindings.NO_KEY)
        set(value) = store.edit().putInt(KEY_SPELL_KEYCODE, value).apply()

    var deleteKeyCode: Int
        get() = store.getInt(KEY_DELETE_KEYCODE, KeyBindings.NO_KEY)
        set(value) = store.edit().putInt(KEY_DELETE_KEYCODE, value).apply()

    var languageKeyCode: Int
        get() = store.getInt(KEY_LANGUAGE_KEYCODE, KeyBindings.NO_KEY)
        set(value) = store.edit().putInt(KEY_LANGUAGE_KEYCODE, value).apply()

    var digitsKeyCode: Int
        get() = store.getInt(KEY_DIGITS_KEYCODE, KeyBindings.NO_KEY)
        set(value) = store.edit().putInt(KEY_DIGITS_KEYCODE, value).apply()

    /**
     * Defaults to the full grid. The remote has nothing printed on it, so a new user has no way
     * to know which key carries which letters — see [HintMode].
     */
    var hintMode: HintMode
        get() = store.getString(KEY_HINT_MODE, null)
            ?.let { stored -> HintMode.entries.firstOrNull { it.name == stored } }
            ?: HintMode.KEYPAD
        set(value) = store.edit().putString(KEY_HINT_MODE, value.name).apply()

    /**
     * Whether committed words are added to the user dictionary.
     *
     * On by default, because a T9 keyboard that does not learn cannot type the name of a series
     * twice. Off exists for anyone who would rather it did not, and the field-level refusals in
     * `T9ImeService` apply regardless of this setting — a password is never learnt whatever this
     * says.
     */
    var isLearning: Boolean
        get() = store.getBoolean(KEY_LEARNING, true)
        set(value) = store.edit().putBoolean(KEY_LEARNING, value).apply()

    val customKeys: CustomKeys
        get() = CustomKeys(
            triggerKeyCode,
            spellKeyCode,
            deleteKeyCode,
            languageKeyCode,
            digitsKeyCode,
        )

    fun keyCodeFor(binding: Binding): Int = when (binding) {
        Binding.TRIGGER -> triggerKeyCode
        Binding.SPELL -> spellKeyCode
        Binding.DELETE -> deleteKeyCode
        Binding.LANGUAGE -> languageKeyCode
        Binding.DIGITS -> digitsKeyCode
    }

    fun assign(binding: Binding, keyCode: Int) {
        when (binding) {
            Binding.TRIGGER -> triggerKeyCode = keyCode
            Binding.SPELL -> spellKeyCode = keyCode
            Binding.DELETE -> deleteKeyCode = keyCode
            Binding.LANGUAGE -> languageKeyCode = keyCode
            Binding.DIGITS -> digitsKeyCode = keyCode
        }
    }

    private companion object {
        const val NAME = "t9"
        const val KEY_ENABLED_LANGUAGES = "enabled_languages"
        const val KEY_ACTIVE_LANGUAGE = "active_language"
        const val KEY_TRIGGER_KEYCODE = "trigger_keycode"
        const val KEY_SPELL_KEYCODE = "spell_keycode"
        const val KEY_DELETE_KEYCODE = "delete_keycode"
        const val KEY_LANGUAGE_KEYCODE = "language_keycode"
        const val KEY_DIGITS_KEYCODE = "digits_keycode"
        const val KEY_HINT_MODE = "hint_mode"
        const val KEY_LEARNING = "learning"
    }
}
