package io.github.vagrant326.atvt9.core

/**
 * What one query cost, and where the cost went.
 *
 * The split matters more than the total. A KSPC of 1.4 made of many small disambiguations is a
 * keyboard worth tuning; the same 1.4 made of two words that fell out of the dictionary
 * entirely is a keyboard whose dictionary is the problem, and no amount of ranking work will
 * touch it.
 */
data class Cost(
    val presses: Int,
    val characters: Int,
    val words: Int,
    val wordsMatched: Int,
    val wordsFirstChoice: Int,
    val nextPresses: Int,
    val spelledWords: Int,
) {
    val kspc: Double get() = if (characters == 0) 0.0 else presses.toDouble() / characters

    operator fun plus(other: Cost) = Cost(
        presses + other.presses,
        characters + other.characters,
        words + other.words,
        wordsMatched + other.wordsMatched,
        wordsFirstChoice + other.wordsFirstChoice,
        nextPresses + other.nextPresses,
        spelledWords + other.spelledWords,
    )

    companion object {
        val ZERO = Cost(0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Counts the presses a query would take through [T9Engine].
 *
 * Deliberately pessimistic in one place and optimistic in another, both stated rather than
 * hidden. Pessimistic: a word that is not in either dictionary is spelled out in full by
 * multitap, with no credit for the fact that the user dictionary would hold it from the second
 * time onwards — [warmed] exists to measure that second time separately. Optimistic: multitap
 * timeouts are waits, not presses, so a word needing them costs less here than it feels like.
 *
 * The space between words counts as one press and one character, which is MacKenzie's
 * convention and the reason these figures can sit next to the published ones at all.
 */
class Simulator(private val dictionary: Dictionary?, private val user: UserDictionary) {

    fun cost(query: String): Cost {
        var total = Cost.ZERO
        val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }

        for ((index, word) in words.withIndex()) {
            total += costOf(word)
            if (index < words.size - 1) {
                total += Cost(presses = 1, characters = 1, 0, 0, 0, 0, 0)
            }
        }
        return total
    }

    /**
     * The same query typed a second time, after the first pass has taught the user dictionary.
     *
     * This is the figure that answers whether the adaptive layer earns its place, and it is the
     * one the published T9 number has no equivalent of.
     */
    fun warmed(query: String): Cost {
        for (word in query.trim().split(WHITESPACE)) {
            if (word.isNotEmpty()) {
                user.learn(word)
            }
        }
        return cost(query)
    }

    private fun costOf(word: String): Cost {
        val digits = Keypad.sequenceOf(word)
            ?: return spelled(word) // nothing the keypad can reach: an ampersand, a digit

        val engine = T9Engine(dictionary, user)
        for (digit in digits) {
            engine.press(digit, atMillis = 0)
        }

        val position = engine.candidates.indexOfFirst { it.exact && it.word == word }
        if (position < 0) {
            return spelled(word)
        }
        return Cost(
            presses = digits.length + position,
            characters = word.length,
            words = 1,
            wordsMatched = 1,
            wordsFirstChoice = if (position == 0) 1 else 0,
            nextPresses = position,
            spelledWords = 0,
        )
    }

    /** Multitap, plus the one press that switches into it. */
    private fun spelled(word: String): Cost {
        var presses = 1
        for (character in word) {
            val digit = Keypad.digitOf(character)
            presses += if (digit == null) 1 else Keypad.lettersOn(digit).indexOf(
                character.lowercaseChar()
            ) + 1
        }
        return Cost(
            presses = presses,
            characters = word.length,
            words = 1,
            wordsMatched = 0,
            wordsFirstChoice = 0,
            nextPresses = 0,
            spelledWords = 1,
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
