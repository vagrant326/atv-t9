package io.github.vagrant326.atvt9.core

/** How the pending presses are being read. */
enum class Composer {
    /** Ambiguous: the sequence is looked up and the dictionary decides the letters. */
    WORD,

    /** Unambiguous: one key tapped until the letter appears. See [Multitap]. */
    SPELL,
}

/**
 * One word in progress, and the merge of the two dictionaries behind it.
 *
 * Android-free on purpose. The simulator that produces the KSPC figure and the IME the user
 * installs walk the same state machine, so a measured number describes the keyboard that ships
 * rather than a model of it.
 */
class T9Engine(
    var dictionary: Dictionary?,
    private val user: UserDictionary,
    private val limit: Int = Dictionary.DEFAULT_LIMIT,
) {

    private val digits = StringBuilder()
    private val multitap = Multitap()
    private var cached: List<Candidate> = emptyList()

    var mode: Composer = Composer.WORD
        private set

    /** Which candidate the strip has highlighted, and therefore what commit will produce. */
    var selected: Int = 0
        private set

    val isComposing: Boolean
        get() = if (mode == Composer.WORD) digits.isNotEmpty() else !multitap.isEmpty

    val candidates: List<Candidate>
        get() = cached

    /** The pending key sequence, for the strip to show when nothing matches it. */
    val sequence: String
        get() = digits.toString()

    /**
     * Whether the pending sequence spells anything at all.
     *
     * False is a state the user has to be told about rather than left to infer — the difference
     * between "these letters are wrong" and "these letters do not exist" decides whether they
     * press NEXT or switch to spelling, and on an ambiguous keypad it is invisible otherwise.
     */
    val hasMatch: Boolean
        get() = mode == Composer.SPELL || cached.isNotEmpty()

    /** What the editor should show inline, which is always what commit would produce. */
    val composing: String
        get() = when {
            mode == Composer.SPELL -> multitap.text()
            cached.isNotEmpty() -> cached[selected.coerceIn(cached.indices)].word
            else -> digits.toString()
        }

    fun press(digit: Char, atMillis: Long): Boolean {
        if (!Keypad.isDigit(digit)) {
            return false
        }
        if (mode == Composer.SPELL) {
            return multitap.press(digit, atMillis)
        }
        digits.append(digit)
        refresh()
        return true
    }

    /**
     * Walks to the next candidate. Wraps, because a strip that stops at the end leaves the user
     * pressing a key that does nothing with no way to know why.
     */
    fun next(): Boolean {
        if (mode == Composer.SPELL || cached.size <= 1) {
            return false
        }
        selected = (selected + 1) % cached.size
        return true
    }

    fun select(index: Int): Boolean {
        if (mode == Composer.SPELL || index !in cached.indices) {
            return false
        }
        selected = index
        return true
    }

    fun backspace(): Boolean {
        if (mode == Composer.SPELL) {
            return multitap.backspace()
        }
        if (digits.isEmpty()) {
            return false
        }
        digits.setLength(digits.length - 1)
        refresh()
        return true
    }

    /**
     * Switches to spelling, discarding the pending sequence.
     *
     * Discarding is deliberate. Carrying the digits across would mean re-reading presses that
     * were made under ambiguous rules as if they had been made under unambiguous ones, and the
     * letters that fall out of that are not the ones the user was aiming at — they would have to
     * check and fix every position, which costs more than retyping a word that is on average
     * seven letters long. The retype happens once per new word ever, because [commit] then puts
     * it in the user dictionary.
     */
    fun spell() {
        digits.setLength(0)
        cached = emptyList()
        selected = 0
        multitap.reset()
        mode = Composer.SPELL
    }

    /** Ends the multitap letter in progress, so the same key next starts a new one. */
    fun settle() {
        multitap.settle()
    }

    /**
     * Finishes the word and returns what to insert, or null if nothing was pending.
     *
     * [learn] is the caller's decision and never this class's: the IME withholds it for password
     * and no-personalised-learning fields. A word already in the shipped dictionary is still
     * recorded, because the count is what lifts it above the words it collides with.
     */
    fun commit(learn: Boolean = true): String? {
        if (!isComposing) {
            return null
        }
        val word = composing
        if (learn && word.isNotEmpty() && Keypad.sequenceOf(word) != null) {
            user.learn(word)
        }
        reset()
        return word
    }

    fun reset() {
        digits.setLength(0)
        multitap.reset()
        cached = emptyList()
        selected = 0
        mode = Composer.WORD
    }

    private fun refresh() {
        cached = lookup()
        selected = 0
    }

    /**
     * The two dictionaries, merged.
     *
     * A word present in both keeps the user's score, which is the higher of the two by
     * construction — that is how using a word the dictionary already had still promotes it above
     * the words it shares a sequence with, without a second copy of the shipped vocabulary
     * accumulating in the user layer.
     */
    private fun lookup(): List<Candidate> {
        val pending = digits.toString()
        if (pending.isEmpty()) {
            return emptyList()
        }

        val mine = user.candidates(pending, limit)
        val base = dictionary?.candidates(pending, limit).orEmpty()
        if (mine.isEmpty()) {
            return base
        }

        val merged = LinkedHashMap<String, Candidate>(mine.size + base.size)
        for (candidate in mine + base) {
            val existing = merged[candidate.word]
            merged[candidate.word] = when {
                existing == null -> candidate
                candidate.score > existing.score -> candidate
                else -> existing
            }
        }
        return merged.values
            .sortedWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.score })
            .take(limit)
    }
}
