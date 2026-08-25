package io.github.vagrant326.atvt9.ime

import android.view.KeyEvent

sealed interface Action {

    /** One of `2`-`9`. The ambiguity is resolved by the dictionary, not here. */
    data class Digit(val digit: Char) : Action

    /** Finishes the word and adds a space. `0` on every phone ever made. */
    data object Space : Action

    /** Cycles the marks a query actually needs. `1`, again by convention. */
    data object Punctuation : Action

    data class Candidate(val forward: Boolean) : Action

    /** Finishes the word without a space, and submits if the field wants that. */
    data object Commit : Action

    data object Delete : Action
    data object Spell : Action
    data object NextLanguage : Action

    /** Abandon the word in progress if there is one, otherwise leave the key alone. */
    data object Back : Action

    /** Consume the event and do nothing, which is what a key held down past the first repeat
     *  has to do: a number key that repeated would append letters nobody pressed. */
    data object Ignore : Action
}

/**
 * Custom bindings, because remotes disagree about which keys exist and about what they report.
 * The user's `TEXT` key sits where a phone has `*` and reports keycode 300, well outside the
 * standard range — nothing in the app could have guessed that.
 *
 * All four are optional. Spelling is also reachable by holding a number key, deleting by `BACK`
 * while a word is in progress, and the language switch does nothing with one language enabled.
 * The trigger is the exception: it cannot be reached any other way, because the keyboard is not
 * on screen at the moment it is needed.
 */
data class CustomKeys(val trigger: Int, val spell: Int, val delete: Int, val language: Int)

object KeyBindings {

    const val NO_KEY = 0

    /**
     * Keys the keyboard needs for itself, and therefore cannot be assigned as a binding.
     *
     * Longer than the equivalent list in H4-Writer, and that is the trade this method makes:
     * `0`-`9` *are* the keyboard here, so a remote with number keys gets a keyboard and a remote
     * without one gets nothing. `docs/00-overview.md` §3 relaxes C5 for exactly this reason.
     */
    val RESERVED: Set<Int> = buildSet {
        addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
        add(KeyEvent.KEYCODE_DPAD_LEFT)
        add(KeyEvent.KEYCODE_DPAD_RIGHT)
        add(KeyEvent.KEYCODE_DPAD_CENTER)
        add(KeyEvent.KEYCODE_ENTER)
        add(KeyEvent.KEYCODE_BACK)
        add(KeyEvent.KEYCODE_HOME)
    }

    /**
     * @param repeatCount straight from the [KeyEvent]. Only `1` counts as a hold; later repeats
     *   are swallowed, so one hold is one action rather than a rate.
     * @param composing whether a word is in progress. `BACK` and the arrows mean something only
     *   then — outside a word they belong to whatever is behind the keyboard, and a keyboard
     *   that eats the d-pad on a TV leaves the whole device unnavigable.
     *
     * Returns null for anything this keyboard has no use for, which the service passes through
     * untouched rather than consuming.
     */
    fun of(keyCode: Int, repeatCount: Int, custom: CustomKeys, composing: Boolean): Action? {
        // Holding a number key spells, so a word the dictionary lacks needs no assigned key and
        // no menu. It is the one hold worth its dwell time here: it replaces the discovery of a
        // setting, not two presses.
        if (repeatCount == 1 && keyCode in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9) {
            return Action.Spell
        }
        if (repeatCount > 0) {
            return Action.Ignore
        }

        if (custom.trigger != NO_KEY && keyCode == custom.trigger) {
            return null // handled before the keyboard is showing; see T9ImeService
        }
        if (custom.spell != NO_KEY && keyCode == custom.spell) {
            return Action.Spell
        }
        if (custom.delete != NO_KEY && keyCode == custom.delete) {
            return Action.Delete
        }
        if (custom.language != NO_KEY && keyCode == custom.language) {
            return Action.NextLanguage
        }

        return when (keyCode) {
            in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 ->
                Action.Digit('0' + (keyCode - KeyEvent.KEYCODE_0))

            KeyEvent.KEYCODE_0 -> Action.Space
            KeyEvent.KEYCODE_1 -> Action.Punctuation
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (composing) Action.Candidate(forward = true) else null
            KeyEvent.KEYCODE_DPAD_LEFT -> if (composing) Action.Candidate(forward = false) else null
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Action.Commit
            KeyEvent.KEYCODE_DEL -> Action.Delete
            KeyEvent.KEYCODE_BACK -> if (composing) Action.Back else null
            else -> null
        }
    }
}
