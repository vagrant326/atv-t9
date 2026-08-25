package io.github.vagrant326.atvt9.core

/**
 * The fallback: one key, tapped until the letter wanted appears.
 *
 * Slow by construction — `ż` is six taps of `9` — and that is acceptable because of what it is
 * for. Every word this method types is a word the dictionary did not have, and every word typed
 * this way is added to [UserDictionary] on commit. So the cost is paid once per new word in a
 * lifetime, not once per use, and the alternative is a keyboard that simply cannot type the
 * name of the series the user wants to watch.
 *
 * The clock is a parameter rather than a call to the system, so the timeout is testable and the
 * class stays free of Android.
 */
class Multitap(private val timeoutMillis: Long = DEFAULT_TIMEOUT) {

    private val text = StringBuilder()
    private var lastDigit: Char? = null
    private var lastAt = Long.MIN_VALUE
    private var cycle = 0

    val isEmpty: Boolean get() = text.isEmpty()

    fun text(): String = text.toString()

    /**
     * Registers one press. The same key inside the timeout cycles the letter already placed;
     * anything else starts a new one.
     */
    fun press(digit: Char, atMillis: Long): Boolean {
        val letters = Keypad.lettersOn(digit)
        if (letters.isEmpty()) {
            return false
        }
        if (digit == lastDigit && text.isNotEmpty() && atMillis - lastAt <= timeoutMillis) {
            cycle = (cycle + 1) % letters.length
            text.setCharAt(text.length - 1, letters[cycle])
        } else {
            cycle = 0
            text.append(letters[0])
        }
        lastDigit = digit
        lastAt = atMillis
        return true
    }

    /**
     * Commits the letter in progress so the next press of the same key starts a new one.
     *
     * The IME calls this when the timeout expires. Without it `aa` is unreachable: the second
     * press would always be read as cycling the first.
     */
    fun settle() {
        lastDigit = null
        lastAt = Long.MIN_VALUE
        cycle = 0
    }

    fun backspace(): Boolean {
        if (text.isEmpty()) {
            return false
        }
        text.setLength(text.length - 1)
        settle()
        return true
    }

    fun reset() {
        text.setLength(0)
        settle()
    }

    companion object {
        /**
         * Long by phone standards. A remote is held at arm's length and the letters are not
         * printed on it, so the user is reading the screen between presses rather than typing
         * from memory, and a phone-length timeout commits the letter while they are still
         * looking.
         */
        const val DEFAULT_TIMEOUT = 1_200L
    }
}
