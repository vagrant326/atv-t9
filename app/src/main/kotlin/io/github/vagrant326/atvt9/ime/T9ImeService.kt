package io.github.vagrant326.atvt9.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import io.github.vagrant326.atvt9.core.Composer
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.core.LetterCase
import io.github.vagrant326.atvt9.core.T9Engine
import io.github.vagrant326.atvt9.model.DictionaryRepository
import io.github.vagrant326.atvt9.model.Language
import io.github.vagrant326.atvt9.model.UserWords
import io.github.vagrant326.atvt9.settings.Preferences

/**
 * The keyboard.
 *
 * Number keys carry the letters, one press each, and the dictionary decides which letters they
 * were. What is not standard T9 is the second dictionary underneath: everything committed here
 * is remembered, so a series title that the shipped vocabulary has never heard of costs its
 * multitap price exactly once. Without that this method cannot type the thing a TV search box
 * is mostly used for, and the published KSPC of 1.0072 describes a workload nobody has on a
 * television.
 */
class T9ImeService : InputMethodService() {

    private lateinit var preferences: Preferences
    private lateinit var dictionaries: DictionaryRepository
    private lateinit var userWords: UserWords
    private lateinit var strip: CandidateStripView
    private lateinit var engine: T9Engine

    /**
     * Whether anything typed in the current field may be remembered.
     *
     * Decided once per field, from the editor's own declaration, and never from a heuristic over
     * the text. An adaptive keyboard that records indiscriminately is precisely the artefact
     * `docs/00-overview.md` §3.1 argues this project must not build, and the only defensible
     * place to draw the line is where the app hosting the field has already drawn it.
     */
    private var mayLearn = true

    /**
     * What this keyboard has written into the word standing at the caret, across however many
     * pieces it took to get there, and still open until something ends it.
     *
     * A word the dictionary has never heard of is typed in sessions - `spa`, accept, `jder`,
     * accept - because each piece is something the dictionary can offer and the whole is not.
     * Learning each piece as it was accepted filled the user dictionary with syllables and never
     * recorded the word, so the next time cost exactly the same. The pieces are held here until
     * something ends the word, and the join is what is learnt.
     */
    private val openWord = StringBuilder()

    /**
     * Whether the strip has to hide what is being typed, because the field is hiding it too.
     *
     * Only a masked password field sets this. It is about the room rather than about the device:
     * the editor's dots keep a password off the screen and the candidate strip put it straight
     * back, in the largest type on the display.
     */
    private var masked = false

    /**
     * Whether the user has asked to see the masked field anyway.
     *
     * Per field and cleared when one opens, so a password left on screen in one box cannot follow
     * the user into the next.
     */
    private var revealed = false

    private var punctuationAt = -1

    /**
     * Applied where the word reaches the field, never where it reaches the dictionary. Word-scoped
     * rather than per-character: what is in flight here is a whole word the dictionary has not
     * finished choosing, so there is no single character for a capital to attach to.
     */
    private var letterCase = LetterCase.LOWER

    /** The key whose meaning is waiting on its release. See [Action.DeferToRelease]. */
    private var deferredKey = KeyEvent.KEYCODE_UNKNOWN

    /**
     * Whether `2`-`9` are carrying marks instead of letters, and where in one key's run the mark
     * being cycled has got to.
     *
     * Held here rather than in [T9Engine] because a mark is not a word: it goes straight into the
     * field and is replaced in place while cycled, which is the mechanism key `1` already uses.
     * Keeping it out of the engine is what guarantees a symbol can never reach the dictionary or
     * a candidate list.
     */
    private var symbols = false
    private var symbolKey: Char? = null
    private var symbolAt = 0

    /**
     * Digits instead of letters. Set by the field when it asks for a number, and by the user's
     * key otherwise — a numeric field that offered word candidates would be offering nonsense.
     */
    private var digits = false

    private var showLanguages = false

    /**
     * Whether the field is not a text editor at all and only understands key events.
     *
     * This is what Netflix and YouTube search give a raised keyboard: the framework's fallback
     * connection, which holds no text and turns each commit into key events - real ones for a
     * single character, one `ACTION_MULTIPLE` for anything longer, which those apps ignore. So a
     * whole word committed at once never arrived. Here the word is kept off the connection, and
     * anything that would read or edit the text through it is replaced by keys, because there is
     * no text. Set from `TYPE_NULL` when the field admits it, and from the first commit when it
     * does not - see [finishWord].
     */
    private var raw = false

    override fun onCreate() {
        super.onCreate()
        preferences = Preferences(this)
        dictionaries = DictionaryRepository(this)
        userWords = UserWords.of(this)
        engine = T9Engine(null, userWords.dictionary)
    }

    override fun onCreateInputView(): View {
        strip = CandidateStripView(this)
        return strip
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        engine.dictionary = dictionaries.dictionaryFor(preferences.activeLanguage)
        mayLearn = preferences.isLearning && isLearnable(info)
        showLanguages = false
        deferredKey = KeyEvent.KEYCODE_UNKNOWN
        openWord.setLength(0)
        raw = (info?.inputType ?: InputType.TYPE_NULL) == InputType.TYPE_NULL

        val variation = info?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: 0
        val classification = info?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0

        // A password is in no dictionary by construction, so every candidate offered against one
        // is wrong and the user pays a hold of `1` per run to get out of the prediction. Set
        // before the reset below, which is what puts the first word into spelling.
        engine.spellByDefault = isPassword(classification, variation)
        engine.reset()

        // The editor masks a password and the strip did not, and on a television the strip is the
        // legible one — it draws the word in progress a metre high across the room. A
        // visible-password field has already decided to show the text, so there is nothing left
        // for the strip to hide.
        masked = engine.spellByDefault &&
            variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        revealed = false

        // Like the digit mode below, the case and the mark layer belong to the field and not to
        // the app: neither a lock nor a half-used layer may follow the user into the next box.
        letterCase = LetterCase.LOWER
        leaveSymbols()

        // A field that wants a number gets digits without being asked. Anything else starts in
        // letters even if the mode was left on: the mode belongs to the field, not to the app.
        digits = classification == InputType.TYPE_CLASS_NUMBER ||
            classification == InputType.TYPE_CLASS_PHONE ||
            classification == InputType.TYPE_CLASS_DATETIME
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
    }

    override fun onFinishInput() {
        finishWord(commit = false)
        // Leaving the field ends the word whether or not a separator ever arrived, which is the
        // common case in a search box: the last thing typed is submitted, not spaced.
        learnOpenWord()
        userWords.flush()
        super.onFinishInput()
    }

    /**
     * Never. The default says yes to every landscape screen, and a television is landscape
     * always — so leaving this alone puts the keyboard into extract mode permanently, which
     * covers the whole display with a white text editor and hides the field the user was
     * actually filling in. It reads as the keyboard failing to open rather than as a mode.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The keyboard is not always visible when a key arrives, and this is where a previous
     * version of a sibling app left a television unnavigable: consuming d-pad events while
     * hidden means nothing on the device can be reached any more.
     *
     * So while hidden exactly one key is looked at — the trigger the user assigned, unassigned
     * by default — and every other event is handed straight back to the system.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) {
            val trigger = preferences.triggerKeyCode
            if (trigger != KeyBindings.NO_KEY && keyCode == trigger && event.repeatCount == 0) {
                // requestShowSelf is the supported route and arrived in API 28. Below that
                // showWindow is the only way in, and it is what every IME used before 28.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    requestShowSelf(0)
                } else {
                    @Suppress("DEPRECATION")
                    showWindow(true)
                }
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        val action = KeyBindings.of(
            keyCode,
            event.repeatCount,
            preferences.customKeys,
            engine.isComposing,
            digits,
            symbols,
        ) ?: return super.onKeyDown(keyCode, event)

        return handle(action)
    }

    /**
     * `0` and `1` commit nothing until they are released, because they are the two keys here that
     * mean more than one thing.
     *
     * Android delivers a hold as a second key-down, so whatever the tap did has already happened
     * by the time the hold announces itself. `0` wrote a space, which then had to be taken back
     * in front of the user. `1` was worse: its tap commits the word in progress before writing a
     * mark, so the hold that was meant to reach spelling found no word to spell and opened the
     * marks instead — which is the state "hold 1 to spell it" was promising to escape.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != deferredKey) {
            return super.onKeyUp(keyCode, event)
        }
        deferredKey = KeyEvent.KEYCODE_UNKNOWN
        return handle(
            when {
                keyCode != KeyEvent.KEYCODE_1 -> Action.Space
                digits -> Action.Digit('1')
                else -> Action.Punctuation
            }
        )
    }

    private fun handle(action: Action): Boolean {
        // The mark layer is spent by one mark, and cycling that mark is a run of presses on the
        // same key. Anything else ends the layer *before* it is handled, so a letter press that
        // follows a mark comes out of the letter run and not the symbol run.
        if (symbols && !continuesMark(action)) {
            leaveSymbols()
        }

        when (action) {
            is Action.Ignore -> Unit

            is Action.ToggleSymbols -> {
                // The hold has claimed the press, so the release must not also write a mark.
                deferredKey = KeyEvent.KEYCODE_UNKNOWN
                finishWord(commit = true)
                val entering = !symbols
                leaveSymbols()
                symbols = entering
            }

            is Action.Digit -> {
                if (symbols) {
                    cycleMark(action.digit)
                } else if (digits) {
                    // Deterministic: nothing to disambiguate, so it goes straight into the field
                    // rather than through the engine, which would offer words for it. A digit
                    // ends the word too: the dictionary holds nothing carrying one, so a run
                    // continued through it could never be learnt at all.
                    finishWord(commit = true)
                    learnOpenWord()
                    currentInputConnection?.commitText(action.digit.toString(), 1)
                } else {
                    engine.press(action.digit, System.currentTimeMillis())
                    setComposing()
                }
            }

            is Action.Candidate -> {
                if (action.forward) {
                    engine.next()
                } else {
                    // Stepping back is the forward cycle taken all the way round. The strip
                    // holds eight candidates at most, so the loop is bounded by the strip rather
                    // than by the dictionary and there is no state to keep in step.
                    repeat((engine.candidates.size - 1).coerceAtLeast(0)) { engine.next() }
                }
                setComposing()
            }

            is Action.Space -> {
                finishWord(commit = true)
                learnOpenWord()
                currentInputConnection?.commitText(" ", 1)
            }

            // OK accepts a piece and leaves the word open, which is what makes a word the
            // dictionary lacks typable at all. With nothing pending there is no piece to accept,
            // so the word is over and this is the field's own key.
            is Action.Commit -> {
                val wasComposing = engine.isComposing
                finishWord(commit = true)
                if (!wasComposing) {
                    learnOpenWord()
                    // Nothing pending, so this press belongs to the field: a search box wants
                    // to search, and swallowing it would strand the user on a filled-in query.
                    return sendDefaultEditorAction(true)
                }
            }

            is Action.Delete -> {
                if (!engine.backspace()) {
                    deleteBeforeCursor()
                    openWord.setLength((openWord.length - 1).coerceAtLeast(0))
                } else {
                    setComposing()
                }
            }

            is Action.Back -> {
                engine.reset()
                currentInputConnection?.finishComposingText()
            }

            is Action.Spell -> {
                // As above: reached by holding `1`, whose release would otherwise punctuate the
                // word the user has just asked to spell.
                deferredKey = KeyEvent.KEYCODE_UNKNOWN
                engine.spell()
                setComposing()
            }

            is Action.Punctuation -> punctuate()

            is Action.DeferToRelease -> deferredKey = action.keyCode

            /**
             * The word in progress is still the composing region, so the switch applies to it as
             * well as to what follows. Pressing the case key after seeing the wrong case is the
             * order people actually press them in, and making that a delete-and-retype would waste
             * the one advantage a composing region has.
             */
            is Action.ToggleCase -> {
                // The hold has claimed the press, so the release must not also write a space.
                deferredKey = KeyEvent.KEYCODE_UNKNOWN
                letterCase = letterCase.next()
                setComposing()
            }

            is Action.NextLanguage -> nextLanguage()

            is Action.ShowLanguages -> {
                // Cycling blind is fine for two and unusable past that, so a hold names them.
                showLanguages = preferences.enabledLanguages.size > 1
            }

            is Action.ToggleDigits -> {
                // Reached by holding `1` in the digits, whose release would otherwise type one.
                deferredKey = KeyEvent.KEYCODE_UNKNOWN
                finishWord(commit = true)
                learnOpenWord()
                digits = !digits
            }

            // Nothing is hidden outside a masked field, so there the key does nothing rather than
            // arming a state that would be found already on in the next password box.
            is Action.ToggleReveal -> {
                if (masked) {
                    revealed = !revealed
                }
            }

            /**
             * The caret, a word at a time. The word in progress is committed first: leaving it
             * composing while the caret walks away puts the editor's composing region somewhere
             * the user is no longer looking, and what it does next is the editor's business.
             */
            is Action.WordJump -> {
                finishWord(commit = true)
                learnOpenWord()
                jumpWord(action.forward)
            }

            // The word is being destroyed rather than finished, so it goes with it unlearnt.
            is Action.WordDelete -> {
                finishWord(commit = false)
                openWord.setLength(0)
                deleteWord()
            }
        }
        render()
        return true
    }

    /**
     * Moves the caret to the next or previous word boundary.
     *
     * Reads the text around the cursor from the editor rather than tracking a buffer here. The
     * editor owns the text — it may already contain something this keyboard never typed, and a
     * local copy would be wrong the moment it did.
     */
    private fun jumpWord(forward: Boolean) {
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = extracted.text ?: return
        val at = extracted.selectionEnd.coerceIn(0, text.length)

        var target = at
        if (forward) {
            while (target < text.length && text[target].isWhitespace()) target++
            while (target < text.length && !text[target].isWhitespace()) target++
        } else {
            while (target > 0 && text[target - 1].isWhitespace()) target--
            while (target > 0 && !text[target - 1].isWhitespace()) target--
        }
        connection.setSelection(target, target)
    }

    /** Deletes back to the previous word boundary, whitespace included. */
    private fun deleteWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(MAX_CONTEXT, 0) ?: return
        if (before.isEmpty()) {
            return
        }
        var count = 0
        while (count < before.length && before[before.length - 1 - count].isWhitespace()) count++
        while (count < before.length && !before[before.length - 1 - count].isWhitespace()) count++
        connection.deleteSurroundingText(count, 0)
    }

    /**
     * Cycles the marks on `1`, replacing the previous one in place.
     *
     * A query needs a handful of marks and a T9 keypad has one key spare for them, so cycling is
     * the only arrangement that fits. Replacing in place rather than appending is what makes a
     * wrong choice one more press instead of a delete and a retry.
     */
    /**
     * Whether [action] belongs to the mark currently being cycled.
     *
     * A swallowed key repeat does not end the layer, and neither does the toggle itself — asking
     * afterwards would find the layer already gone and it could be entered but never left. The
     * first press after entering has no key to match, which is why a null [symbolKey] continues.
     */
    private fun continuesMark(action: Action): Boolean = when (action) {
        is Action.Ignore, is Action.ToggleSymbols -> true
        is Action.Digit -> symbolKey == null || action.digit == symbolKey
        else -> false
    }

    private fun leaveSymbols() {
        symbols = false
        symbolKey = null
        symbolAt = 0
    }

    /**
     * Commits one mark, replacing the previous one in place while the same key is being tapped.
     *
     * The same bargain [punctuate] makes on key `1`, and for the same reason: a wrong choice costs
     * one more press rather than a delete and a retry. Nothing here goes through the engine, so a
     * mark cannot be learnt, cannot be offered as a candidate and cannot collide with a word.
     */
    private fun cycleMark(digit: Char) {
        val run = Keypad.symbolsOn(digit)
        if (run.isEmpty()) {
            return
        }
        if (digit == symbolKey) {
            symbolAt = (symbolAt + 1) % run.length
            deleteBeforeCursor()
        } else {
            finishWord(commit = true)
            learnOpenWord()
            symbolKey = digit
            symbolAt = 0
        }
        currentInputConnection?.commitText(run[symbolAt].toString(), 1)
    }

    private fun punctuate() {
        // Worked out before [finishWord], which clears the position — that reset is how every
        // other action breaks the run, and this is the one caller that has to survive it.
        val next = if (punctuationAt < 0) 0 else (punctuationAt + 1) % PUNCTUATION.length
        finishWord(commit = true)
        learnOpenWord()
        val connection = currentInputConnection ?: return
        if (next > 0) {
            deleteBeforeCursor()
        }
        connection.commitText(PUNCTUATION[next].toString(), 1)
        punctuationAt = next
    }

    private fun nextLanguage() {
        val enabled = preferences.enabledLanguages
        // One language has nothing to switch between, and a key that silently does nothing is
        // worse than one that does not exist — so the word in progress is left alone too.
        if (enabled.size < 2) {
            return
        }
        finishWord(commit = true)
        learnOpenWord()
        val next = enabled[(enabled.indexOf(preferences.activeLanguage) + 1) % enabled.size]
        preferences.activeLanguage = next
        engine.dictionary = dictionaries.dictionaryFor(next)
    }

    /**
     * Learns the word standing at the caret and closes it.
     *
     * Called where a word ends rather than where a piece is accepted: a space, a mark, a digit,
     * a caret jump, a language change, submitting, leaving the field.
     *
     * The record of what was typed is kept here, but what is learnt is checked against the
     * editor first. The bare arrows fall through to whatever is behind the keyboard when no word
     * is in progress, and the editor moves the caret with them without telling this service - so
     * an open word can have been left behind somewhere else in the field, and gluing it to
     * whatever was typed next would invent a word nobody wrote. If the text at the caret is no
     * longer what was recorded, nothing is learnt.
     */
    private fun learnOpenWord() {
        val text = openWord.toString()
        openWord.setLength(0)
        if (!mayLearn || text.isEmpty()) {
            return
        }
        // A raw field has no text to check against and no caret to have moved, and refusing there
        // would mean nothing typed into Netflix or YouTube is ever learnt.
        if (!raw && currentInputConnection?.getTextBeforeCursor(text.length, 0)?.toString() != text) {
            return
        }
        if (userWords.dictionary.learn(text)) {
            // Cheap enough per word, and the alternative is losing everything learnt in a
            // session when the system reclaims the keyboard process without warning.
            userWords.flush()
        }
    }

    /** Shows the pending word inline, so the field always reads as what committing would leave. */
    private fun setComposing() {
        punctuationAt = -1
        // A raw field cannot take a word back, so the strip is the only place it can be shown.
        if (raw) {
            return
        }
        val connection = currentInputConnection ?: return
        if (engine.isComposing) {
            connection.setComposingText(letterCase.apply(engine.composing), 1)
        } else {
            connection.finishComposingText()
        }
    }

    private fun finishWord(commit: Boolean) {
        punctuationAt = -1
        if (!engine.isComposing) {
            return
        }
        val connection = currentInputConnection
        if (commit) {
            // The engine is handed the word in lower case and learns it that way. The capital is
            // applied to what goes into the *field* and never to what goes into the dictionary: a
            // user dictionary holding both `jan` and `Jan` would answer one key sequence twice and
            // carry the duplicate for ever, which is the sort of rot only the user can clear.
            // Never learnt here: a commit ends a piece, and a piece is not yet a word. See
            // [openWord] and [learnOpenWord], where the join reaches the dictionary.
            val word = engine.commit(learn = false)
            if (word != null) {
                val text = letterCase.apply(word)
                // A character at a time everywhere, not only where [raw] is already known: the
                // first commit replaces the composing region and the rest append, which a real
                // editor ends up with as the same word, and it is the only form a fallback
                // connection turns into keys.
                text.forEach { char ->
                    val base = DIACRITIC_KEYS[char.lowercaseChar()]
                    if (raw && base != null) {
                        probeDiacritic(base, char.isUpperCase())
                    } else {
                        connection?.commitText(char.toString(), 1)
                    }
                    // Netflix shows no text field and only reaches the keyboard through the
                    // trigger key, yet checking for `TYPE_NULL` did not find it. What gives the
                    // fallback away is that it forgets each character as soon as it has been
                    // sent as a key.
                    if (connection?.getTextBeforeCursor(1, 0)?.isEmpty() == true) {
                        raw = true
                    }
                }
                openWord.append(text)
                letterCase = letterCase.afterWord()
            }
        } else {
            engine.reset()
            connection?.finishComposingText()
        }
    }

    /**
     * PROOF OF CONCEPT, to be reverted: a raw field drops any character without a key on the
     * virtual keymap, which is all nine Polish ones. Each is sent as three chords on the Polish
     * programmer layout, numbered so one photo of the search box shows which one the app took:
     * `1` right Alt, `2` left Alt, `3` Ctrl+Alt.
     */
    private fun probeDiacritic(key: Int, upper: Boolean) {
        val shift = if (upper) listOf(KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT) else emptyList()
        val chords = listOf(
            listOf(KeyEvent.KEYCODE_ALT_RIGHT to ALT_RIGHT),
            listOf(KeyEvent.KEYCODE_ALT_LEFT to ALT_LEFT),
            listOf(KeyEvent.KEYCODE_CTRL_LEFT to CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT to ALT_LEFT),
        )
        chords.forEachIndexed { index, modifiers ->
            currentInputConnection?.commitText((index + 1).toString(), 1)
            sendChord(modifiers + shift, key)
        }
    }

    private fun sendChord(modifiers: List<Pair<Int, Int>>, key: Int) {
        val connection = currentInputConnection ?: return
        val down = SystemClock.uptimeMillis()
        var meta = 0
        fun send(action: Int, code: Int) {
            connection.sendKeyEvent(
                KeyEvent(
                    down, SystemClock.uptimeMillis(), action, code, 0, meta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                )
            )
        }
        for ((code, flag) in modifiers) {
            meta = meta or flag
            send(KeyEvent.ACTION_DOWN, code)
        }
        send(KeyEvent.ACTION_DOWN, key)
        send(KeyEvent.ACTION_UP, key)
        for ((code, flag) in modifiers.asReversed()) {
            send(KeyEvent.ACTION_UP, code)
            meta = meta and flag.inv()
        }
    }

    private fun deleteBeforeCursor() {
        if (raw) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun render() {
        if (!::strip.isInitialized) {
            return
        }
        // Read from the editor rather than from a copy kept here, for the same reason the caret
        // and the word delete do: the field owns the text and may hold a password this keyboard
        // never typed - one the user pasted, or one a password manager filled in. An editor is
        // free to refuse a masked field, and then the run in progress is all there is to show.
        val revealText = if (masked && revealed) {
            currentInputConnection?.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString()
                ?: letterCase.apply(engine.composing)
        } else {
            null
        }
        strip.render(
            StripState(
                candidates = engine.candidates,
                selected = engine.selected,
                sequence = engine.sequence,
                composing = engine.composing,
                spelling = engine.mode == Composer.SPELL,
                trained = engine.dictionary != null,
                language = languageLabel(),
                hintMode = preferences.hintMode,
                letterCase = letterCase,
                symbols = symbols,
                digits = digits,
                hasEditor = currentInputConnection != null,
                learning = mayLearn,
                masked = masked,
                revealText = revealText,
                customKeys = preferences.customKeys,
            )
        )
    }

    private fun languageLabel(): String =
        if (preferences.enabledLanguages.size <= 1) "" else preferences.activeLanguage.label

    /**
     * Whether this field's contents may be added to the user dictionary.
     *
     * Three separate refusals, because the apps that set them mean three different things and
     * only one of them is about secrecy. A password must never be stored; a field that asked for
     * no personalised learning has told us not to; a no-suggestions field is usually an
     * identifier, and an identifier in the dictionary is noise the user then has to delete.
     */
    private fun isLearnable(info: EditorInfo?): Boolean {
        if (info == null) {
            return false
        }
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            return false
        }
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val classification = info.inputType and InputType.TYPE_MASK_CLASS
        if (isPassword(classification, variation)) {
            return false
        }
        if (classification == InputType.TYPE_CLASS_TEXT) {
            if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
                return false
            }
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Whether the field holds a password, in all four ways Android has of saying so.
     *
     * Two callers ask, and they want different things from the answer — one decides what may be
     * remembered, the other what may be drawn and whether the keyboard predicts at all. They must
     * never disagree about which field this is, which is why the list of variations lives here
     * once rather than at each of them.
     */
    private fun isPassword(classification: Int, variation: Int): Boolean = when (classification) {
        InputType.TYPE_CLASS_TEXT ->
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }

    private companion object {
        /** What a TV query actually contains. Not a general punctuation set, and not meant as one. */
        const val PUNCTUATION = ".,-'&:/"

        /**
         * How much text before the caret a word-delete will look at. A TV query is a line, so
         * this is far more than one word ever needs — the cap exists because the editor is under
         * no obligation to be small and a novel would be copied across the process boundary.
         */
        const val MAX_CONTEXT = 512

        val DIACRITIC_KEYS = mapOf(
            'ą' to KeyEvent.KEYCODE_A,
            'ć' to KeyEvent.KEYCODE_C,
            'ę' to KeyEvent.KEYCODE_E,
            'ł' to KeyEvent.KEYCODE_L,
            'ń' to KeyEvent.KEYCODE_N,
            'ó' to KeyEvent.KEYCODE_O,
            'ś' to KeyEvent.KEYCODE_S,
            'ź' to KeyEvent.KEYCODE_X,
            'ż' to KeyEvent.KEYCODE_Z,
        )

        const val ALT_RIGHT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        const val ALT_LEFT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        const val CTRL_LEFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    }
}
