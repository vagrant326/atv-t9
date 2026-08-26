package io.github.vagrant326.atvt9.core

/**
 * Whether the next word is capitalised, and for how long.
 *
 * One gesture and three states rather than a key for the one-off and another for the lock: this
 * remote has no spare buttons, and `KeyBindings.RESERVED` says why — `0`-`9` *are* the keyboard.
 *
 * The order is a measurement, not a taste. A corpus records how many capitals a language contains
 * and also how many of them stand alone, and isolated capitals — sentence openings, proper nouns
 * — outnumber runs of them by a wide margin in both alphabets here. So the first press buys the
 * common case and the lock costs one more, which is where every phone keypad ended up.
 *
 * **Word-scoped, unlike the sibling keyboards.** In multitap the case applies to one character
 * because one character is what is in flight; here what is in flight is a whole word that the
 * dictionary has not finished choosing yet, so [ONCE] means "capitalise this word" and [LOCKED]
 * means "shout it". Applying it per character would mean asking the user to hold the gesture
 * across presses whose letters are not decided.
 */
enum class LetterCase {

    LOWER,

    /** This word only, and then back to [LOWER] on its own. */
    ONCE,

    /** Every word until switched off. */
    LOCKED,
    ;

    fun next(): LetterCase = entries[(ordinal + 1) % entries.size]

    /**
     * Digits and marks come back unchanged, which is why this is applied to everything the
     * keyboard writes rather than only to words: a caller that has to ask first is a caller that
     * will eventually forget to.
     *
     * `uppercaseChar` rather than `uppercase`: the locale-aware version would make `İ` out of `i`
     * on a Turkish device, and it exists to cover languages where one letter becomes two — none
     * of which occur in either alphabet here. The whole Polish set maps one to one, `ł` to `Ł`
     * included.
     */
    fun apply(word: String): String = when (this) {
        LOWER -> word
        ONCE -> word.replaceFirstChar { it.uppercaseChar() }
        LOCKED -> word.map { it.uppercaseChar() }.joinToString("")
    }

    /**
     * What the state becomes once a word has actually reached the field.
     *
     * Only a word spends [ONCE]. A space, a mark or a digit cannot be capitalised, so consuming
     * the state on one would quietly take back the capital the user asked for — and with nothing
     * printed on the remote, the only way they could find out is by reading the result and not
     * believing it.
     */
    fun afterWord(): LetterCase = if (this == ONCE) LOWER else this
}
