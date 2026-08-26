package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LetterCaseTest {

    @Test
    fun `one gesture reaches every state and comes back`() {
        assertEquals(LetterCase.ONCE, LetterCase.LOWER.next())
        assertEquals(LetterCase.LOCKED, LetterCase.ONCE.next())
        assertEquals(
            LetterCase.LOWER,
            LetterCase.LOCKED.next(),
            "a cycle that cannot be left strands the user in a mode the remote does not show",
        )
    }

    @Test
    fun `the one-off capitalises the word rather than everything`() {
        // Word-scoped, which is the difference from the sibling keyboards: what is in flight here
        // is a whole word the dictionary has not finished choosing.
        assertEquals("Jan", LetterCase.ONCE.apply("jan"))
        assertEquals("JAN", LetterCase.LOCKED.apply("jan"))
        assertEquals("jan", LetterCase.LOWER.apply("jan"))
    }

    @Test
    fun `a Polish word capitalises on its first letter`() {
        assertEquals("Łódź", LetterCase.ONCE.apply("łódź"))
        assertEquals("ŁÓDŹ", LetterCase.LOCKED.apply("łódź"))
    }

    @Test
    fun `every Polish letter has a capital and keeps it`() {
        val pairs = mapOf(
            'ą' to 'Ą', 'ć' to 'Ć', 'ę' to 'Ę', 'ł' to 'Ł', 'ń' to 'Ń',
            'ó' to 'Ó', 'ś' to 'Ś', 'ź' to 'Ź', 'ż' to 'Ż',
        )
        for ((lower, upper) in pairs) {
            assertEquals(upper.toString(), LetterCase.LOCKED.apply(lower.toString()))
        }
    }

    @Test
    fun `the whole keypad is reachable in capitals`() {
        val letters = (Keypad.FIRST_DIGIT..Keypad.LAST_DIGIT)
            .joinToString("") { Keypad.lettersOn(it) }
        val capitals = LetterCase.LOCKED.apply(letters)
        assertEquals(
            letters.length,
            capitals.toSet().size,
            "two letters sharing one capital would make a password unreachable, not merely odd",
        )
    }

    @Test
    fun `marks and digits pass through untouched`() {
        assertEquals(".,-'&:/", LetterCase.LOCKED.apply(".,-'&:/"))
        assertEquals("2026", LetterCase.LOCKED.apply("2026"))
        assertEquals("2026", LetterCase.ONCE.apply("2026"))
    }

    @Test
    fun `an empty word does not throw`() {
        // `finishWord` only calls this on a committed word, but a candidate list can go empty
        // between a press and a render and this must not be the thing that crashes the keyboard.
        assertEquals("", LetterCase.ONCE.apply(""))
        assertEquals("", LetterCase.LOCKED.apply(""))
    }

    @Test
    fun `a word spends the one-off and nothing else does`() {
        assertEquals(LetterCase.LOWER, LetterCase.ONCE.afterWord())
        assertEquals(
            LetterCase.LOCKED,
            LetterCase.LOCKED.afterWord(),
            "the lock is the state that survives words; that is the whole difference",
        )
        assertEquals(LetterCase.LOWER, LetterCase.LOWER.afterWord())
    }
}
