package io.github.vagrant326.atvt9.core

/**
 * ITU E.161, the layout printed on every phone made between 1995 and 2007 and on the number
 * keys of the user's remote.
 *
 * The Polish letters fold onto their base key, which is not a compromise — it is the reason to
 * build this at all. Under multitap `ż` is five presses of `9` and the user has to know it is
 * on `9`; here it is one press of `9` and the dictionary decides between `z` and `ż`. Nothing
 * is printed on the remote, so a mapping the user has to be taught is a mapping they will not
 * use, and E.161 is the only one they already know.
 */
object Keypad {

    const val FIRST_DIGIT = '2'
    const val LAST_DIGIT = '9'

    private val KEYS = mapOf(
        '2' to "abcąć",
        '3' to "defę",
        '4' to "ghi",
        '5' to "jklł",
        '6' to "mnońó",
        '7' to "pqrsś",
        '8' to "tuv",
        '9' to "wxyzźż",
    )

    private val BY_LETTER: Map<Char, Char> =
        KEYS.entries.flatMap { (digit, letters) -> letters.map { it to digit } }.toMap()

    /** Every letter any supported language uses, in the order the keys lay them out. */
    val LETTERS: String = KEYS.keys.sorted().joinToString("") { KEYS.getValue(it) }

    fun lettersOn(digit: Char): String = KEYS[digit] ?: ""

    fun digitOf(letter: Char): Char? = BY_LETTER[letter.lowercaseChar()]

    fun isDigit(character: Char): Boolean = character in FIRST_DIGIT..LAST_DIGIT

    /**
     * The key sequence that produces [word], or null if any character has no key.
     *
     * A null here is not an error to report — it is how the caller learns a word cannot be
     * reached by digits at all, which is true of anything with a digit, an apostrophe or a
     * space in it, and those have to fall through to the fallback method rather than be
     * silently dropped from the dictionary.
     */
    fun sequenceOf(word: CharSequence): String? {
        val digits = StringBuilder(word.length)
        for (character in word) {
            digits.append(digitOf(character) ?: return null)
        }
        return digits.toString()
    }
}
