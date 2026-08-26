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

    /**
     * The caret, one word at a time, from holding left or right outside a word.
     *
     * Held rather than tapped because caret movement is inherently repetitive and a TV query is
     * eleven characters: as single steps, walking back over one word is most of the query. Only
     * outside a word — while composing, left and right are the candidate walk, which is the
     * hotter path by a wide margin.
     */
    data class WordJump(val forward: Boolean) : Action

    data object Delete : Action

    /** The rest of the word, from holding delete. The tap already took one character. */
    data object WordDelete : Action

    data object Spell : Action
    data object NextLanguage : Action

    /** The language list, from holding the language key. Cycling blind past two is unusable. */
    data object ShowLanguages : Action

    /** Digits instead of letters, for a field that wants a number and says so. */
    data object ToggleDigits : Action

    /**
     * `abc` → `Abc` → `ABC` → `abc`, from holding `0`.
     *
     * Held rather than tapped because there is nothing left to tap: the reserved list below is the
     * whole numeric row and the whole d-pad. `0` is the one key whose short press has no run to
     * interfere with — it ends the word and writes a space — so it is the only one that can carry
     * a second meaning without a letter press becoming ambiguous.
     */
    data object ToggleCase : Action

    /**
     * Swaps the letters on `2`-`9` for the full set of QWERTY marks, from holding `1` outside a
     * word.
     *
     * Spent by one mark, so it cannot be left on: an address needs `@` once and a password needs
     * `!` once. Cycling that one mark is a run of presses on the same key, exactly as key `1`
     * cycles its seven — anything else ends the layer before it is handled, so the letter the user
     * meant comes from the letter run rather than the symbol run.
     *
     * It never reaches the engine. A mark is committed straight into the field and replaced in
     * place while it is being cycled, which is the mechanism key `1` already uses — so nothing
     * here can end up in the dictionary or in a candidate list.
     */
    data object ToggleSymbols : Action

    /**
     * A key whose meaning is not settled yet: released, `0` is a space; held, it is [ToggleCase].
     * Resolved in `T9ImeService.onKeyUp`.
     *
     * Android delivers a hold as a *second* key-down after the first, so a space written on the
     * way down would already be in the field by the time the hold announced itself — and
     * un-typing it is visible. `2`-`9` need no deferral: they only extend a sequence the engine
     * can revise for free. Ported from LetterWise, which hit this first.
     */
    data class DeferToRelease(val keyCode: Int) : Action

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
 * All five are optional. Spelling is also a held `1`, deleting is `DPAD_UP` unconditionally, the
 * language switch does nothing with one language enabled, and the digit mode turns itself on in a
 * numeric field. The trigger is the exception: it cannot be reached any other way, because the
 * keyboard is not on screen at the moment it is needed.
 */
data class CustomKeys(
    val trigger: Int,
    val spell: Int,
    val delete: Int,
    val language: Int,
    val digits: Int,
)

object KeyBindings {

    const val NO_KEY = 0

    /**
     * Keys the keyboard needs for itself, and therefore cannot be assigned as a binding.
     *
     * Longer than the equivalent list in H4-Writer, and that is the trade this method makes:
     * `0`-`9` *are* the keyboard here, so a remote with number keys gets a keyboard and a remote
     * without one gets nothing. `docs/00-overview.md` §3 relaxes C5 for exactly this reason.
     *
     * The whole d-pad is reserved including up and down. Left, right and CHANNEL_UP/DOWN walk
     * the candidates, so offering them as bindings would let the user assign away the one gesture
     * the method cannot work without; up deletes and down is inert outside a word, and an arrow
     * that became a function while its neighbours still navigated would be a trap either way.
     */
    val RESERVED: Set<Int> = buildSet {
        addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
        add(KeyEvent.KEYCODE_DPAD_UP)
        add(KeyEvent.KEYCODE_DPAD_DOWN)
        add(KeyEvent.KEYCODE_DPAD_LEFT)
        add(KeyEvent.KEYCODE_DPAD_RIGHT)
        add(KeyEvent.KEYCODE_DPAD_CENTER)
        add(KeyEvent.KEYCODE_ENTER)
        add(KeyEvent.KEYCODE_BACK)
        add(KeyEvent.KEYCODE_HOME)
    }

    /**
     * @param repeatCount straight from the [KeyEvent]. Only `1` counts as a hold; later repeats
     *   are swallowed, so one hold is one action rather than a rate. That is what keeps a held
     *   caret from crossing the whole field — Android repeats at roughly twenty a second.
     * @param composing whether a word is in progress. `BACK` and the left and right arrows mean
     *   something only then — outside a word they belong to whatever is behind the keyboard,
     *   which is how the caret still works and how `BACK` still dismisses. That passthrough is
     *   also the escape hatch: a keyboard that ate the whole d-pad on a TV would leave the device
     *   unnavigable.
     * @param digits whether the number keys are typing digits rather than letters.
     *
     * Returns null for anything this keyboard has no use for, which the service passes through
     * untouched rather than consuming.
     */
    fun of(
        keyCode: Int,
        repeatCount: Int,
        custom: CustomKeys,
        composing: Boolean,
        digits: Boolean,
    ): Action? {
        val longPress = repeatCount == 1

        if (custom.language != NO_KEY && keyCode == custom.language) {
            return when {
                repeatCount > 1 -> Action.Ignore
                longPress -> Action.ShowLanguages
                else -> Action.NextLanguage
            }
        }

        if (longPress) {
            // Spelling hangs off `1` and nothing else. It used to hang off all eight letter
            // keys, which made it invisible: an undiscoverable gesture on a key with no label
            // is the same as no feature. One key can be named on the grid, and is.
            if (keyCode == KeyEvent.KEYCODE_1 && !digits) {
                // One hold, two meanings, chosen by whether there is a word to act on. Spelling
                // is what the strip already advertises for a sequence with no match — "hold 1 to
                // spell it" is shown *while composing* and nowhere else — so that is the state it
                // belongs to. Outside a word there is nothing to spell, and a mark is what the
                // user reaches `1` for anyway.
                return if (composing) Action.Spell else Action.ToggleSymbols
            }
            // Nothing to capitalise in a digit field, and a gesture that silently does nothing is
            // worse than one that is not there.
            if (keyCode == KeyEvent.KEYCODE_0) {
                return if (digits) Action.Ignore else Action.ToggleCase
            }
            if (custom.delete != NO_KEY && keyCode == custom.delete) {
                return Action.WordDelete
            }
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (composing) Action.Ignore else Action.WordJump(forward = false)

                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (composing) Action.Ignore else Action.WordJump(forward = true)

                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DEL -> Action.WordDelete
                else -> Action.Ignore
            }
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
        if (custom.digits != NO_KEY && keyCode == custom.digits) {
            return Action.ToggleDigits
        }

        // In digit mode the row is deterministic: every key is the digit printed on it, and
        // there is nothing to disambiguate, so no candidate walk and no spelling.
        if (digits && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return Action.Digit('0' + (keyCode - KeyEvent.KEYCODE_0))
        }

        return when (keyCode) {
            in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 ->
                Action.Digit('0' + (keyCode - KeyEvent.KEYCODE_0))

            KeyEvent.KEYCODE_0 -> Action.DeferToRelease(keyCode)
            KeyEvent.KEYCODE_1 -> Action.Punctuation

            // Left and right are the candidate walk, because they are under the thumb and the
            // walk happens on most words. Up and down do the same job, and so do CHANNEL_UP and
            // CHANNEL_DOWN, which sit beside the numpad on the remotes that have one — a second
            // way in for a remote whose d-pad is awkward, never the only one.
            // Right walks the candidates and otherwise belongs to the editor, which moves the
            // caret with it — the same thing a right arrow does everywhere else on the device.
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (composing) Action.Candidate(forward = true) else null

            KeyEvent.KEYCODE_DPAD_LEFT -> if (composing) Action.Candidate(forward = false) else null

            // Down walks the candidates and otherwise does nothing, and unlike right it is
            // consumed either way. Left and right are passed through on purpose: the editor moves
            // the caret with them, which is what the user wanted. Down has no such job — a
            // single-line field has no caret to move downwards — so passing it through only threw
            // the focus out of the field, which made the vertical axis read as broken once up
            // started deleting.
            //
            // CHANNEL_UP and CHANNEL_DOWN go the same way, and for one more reason: they sit
            // beside the numpad and are documented here as keyboard keys, so letting them fall
            // through meant a stray press could change channel in the middle of a word.
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                if (composing) Action.Candidate(forward = true) else Action.Ignore

            KeyEvent.KEYCODE_CHANNEL_UP ->
                if (composing) Action.Candidate(forward = false) else Action.Ignore

            // Up deletes, whether or not a word is in progress, and it is the only route that is
            // always there. The others are all conditional in ways the user cannot see: the
            // assigned key needs a spare button and a trip through the settings, `KEYCODE_DEL`
            // needs a remote that has one and a television remote does not, and `BACK` only
            // abandons a word that is already in progress.
            //
            // Up because that is what up already means in H4-Writer's edit mode. It cost the
            // duplicate of the candidate walk that also sits on left and on CHANNEL_UP, which is
            // the cheapest thing on the d-pad to give away.
            KeyEvent.KEYCODE_DPAD_UP -> Action.Delete

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Action.Commit
            KeyEvent.KEYCODE_DEL -> Action.Delete
            KeyEvent.KEYCODE_BACK -> if (composing) Action.Back else null
            else -> null
        }
    }
}
