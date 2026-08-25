package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeypadTest {

    @Test
    fun `every Polish letter folds onto the key its base letter is on`() {
        val folds = mapOf(
            'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l',
            'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
        )
        for ((accented, base) in folds) {
            assertEquals(
                Keypad.digitOf(base),
                Keypad.digitOf(accented),
                "$accented has to sit where $base sits, or the user has to be taught a new keypad",
            )
        }
    }

    @Test
    fun `the alphabet is exactly what the keys carry`() {
        assertEquals(35, Keypad.LETTERS.length)
        assertEquals(Keypad.LETTERS.length, Keypad.LETTERS.toSet().size, "a letter on two keys")
        for (letter in Keypad.LETTERS) {
            assertTrue(Keypad.digitOf(letter) != null)
        }
    }

    @Test
    fun `case does not reach the keypad`() {
        assertEquals(Keypad.digitOf('a'), Keypad.digitOf('A'))
        assertEquals("94339646", Keypad.sequenceOf("Wiedźmin"))
    }

    @Test
    fun `anything the keys cannot reach reports itself rather than being mangled`() {
        // The alternative is filing `don't` under the sequence for `dont` and handing back a
        // mark the user never typed. See corpus/build.py.
        assertNull(Keypad.sequenceOf("don't"))
        assertNull(Keypad.sequenceOf("kot 2"))
        assertNull(Keypad.sequenceOf("black&white"))
    }
}
