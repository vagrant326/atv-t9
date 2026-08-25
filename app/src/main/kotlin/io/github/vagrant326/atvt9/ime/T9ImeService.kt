package io.github.vagrant326.atvt9.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import io.github.vagrant326.atvt9.core.Composer
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

    private var punctuationAt = -1

    /**
     * Digits instead of letters. Set by the field when it asks for a number, and by the user's
     * key otherwise — a numeric field that offered word candidates would be offering nonsense.
     */
    private var digits = false

    private var showLanguages = false

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
        engine.reset()
        engine.dictionary = dictionaries.dictionaryFor(preferences.activeLanguage)
        mayLearn = preferences.isLearning && isLearnable(info)
        showLanguages = false

        // A field that wants a number gets digits without being asked. Anything else starts in
        // letters even if the mode was left on: the mode belongs to the field, not to the app.
        val classification = info?.inputType?.and(InputType.TYPE_MASK_CLASS)
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
        ) ?: return super.onKeyDown(keyCode, event)

        return handle(action)
    }

    private fun handle(action: Action): Boolean {
        when (action) {
            is Action.Ignore -> Unit

            is Action.Digit -> {
                if (digits) {
                    // Deterministic: nothing to disambiguate, so it goes straight into the field
                    // rather than through the engine, which would offer words for it.
                    finishWord(commit = true)
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
                currentInputConnection?.commitText(" ", 1)
            }

            is Action.Commit -> {
                val wasComposing = engine.isComposing
                finishWord(commit = true)
                if (!wasComposing) {
                    // Nothing pending, so this press belongs to the field: a search box wants
                    // to search, and swallowing it would strand the user on a filled-in query.
                    return sendDefaultEditorAction(true)
                }
            }

            is Action.Delete -> {
                if (!engine.backspace()) {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                } else {
                    setComposing()
                }
            }

            is Action.Back -> {
                engine.reset()
                currentInputConnection?.finishComposingText()
            }

            is Action.Spell -> {
                engine.spell()
                setComposing()
            }

            is Action.Punctuation -> punctuate()

            is Action.NextLanguage -> nextLanguage()

            is Action.ShowLanguages -> {
                // Cycling blind is fine for two and unusable past that, so a hold names them.
                showLanguages = preferences.enabledLanguages.size > 1
            }

            is Action.ToggleDigits -> {
                finishWord(commit = true)
                digits = !digits
            }

            /**
             * The caret, a word at a time. The word in progress is committed first: leaving it
             * composing while the caret walks away puts the editor's composing region somewhere
             * the user is no longer looking, and what it does next is the editor's business.
             */
            is Action.WordJump -> {
                finishWord(commit = true)
                jumpWord(action.forward)
            }

            is Action.WordDelete -> {
                finishWord(commit = false)
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
    private fun punctuate() {
        finishWord(commit = true)
        val connection = currentInputConnection ?: return
        punctuationAt = if (punctuationAt < 0) 0 else (punctuationAt + 1) % PUNCTUATION.length
        if (punctuationAt > 0) {
            connection.deleteSurroundingText(1, 0)
        }
        connection.commitText(PUNCTUATION[punctuationAt].toString(), 1)
    }

    private fun nextLanguage() {
        val enabled = preferences.enabledLanguages
        // One language has nothing to switch between, and a key that silently does nothing is
        // worse than one that does not exist — so the word in progress is left alone too.
        if (enabled.size < 2) {
            return
        }
        finishWord(commit = true)
        val next = enabled[(enabled.indexOf(preferences.activeLanguage) + 1) % enabled.size]
        preferences.activeLanguage = next
        engine.dictionary = dictionaries.dictionaryFor(next)
    }

    /** Shows the pending word inline, so the field always reads as what committing would leave. */
    private fun setComposing() {
        punctuationAt = -1
        val connection = currentInputConnection ?: return
        if (engine.isComposing) {
            connection.setComposingText(engine.composing, 1)
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
            val word = engine.commit(learn = mayLearn)
            if (word != null) {
                connection?.commitText(word, 1)
            }
            // Cheap enough per word, and the alternative is losing everything learnt in a
            // session when the system reclaims the keyboard process without warning.
            userWords.flush()
        } else {
            engine.reset()
            connection?.finishComposingText()
        }
    }

    private fun render() {
        if (!::strip.isInitialized) {
            return
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
                digits = digits,
                hasEditor = currentInputConnection != null,
                learning = mayLearn,
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
        if (classification == InputType.TYPE_CLASS_TEXT) {
            if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
                return false
            }
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            ) {
                return false
            }
        }
        if (classification == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return false
        }
        return true
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
    }
}
