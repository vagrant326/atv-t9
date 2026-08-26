package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymbolsTest {

    /** Every printable mark on a US QWERTY keyboard, which is the promise this layer makes. */
    private val qwerty = "`~!@#$%^&*()-_=+[]{}\\|;:'\",.<>/?"

    private fun carried() = (Keypad.FIRST_DIGIT..Keypad.LAST_DIGIT)
        .joinToString("") { Keypad.symbolsOn(it) }

    @Test
    fun `every QWERTY mark is on some key`() {
        val carried = carried()
        for (mark in qwerty) {
            assertTrue(mark in carried, "$mark is unreachable, which is the whole bug this fixes")
        }
    }

    @Test
    fun `no mark is on two keys`() {
        val carried = carried()
        assertEquals(qwerty.length, carried.length)
        assertEquals(carried.length, carried.toSet().size)
    }

    @Test
    fun `nothing costs more than four taps`() {
        for (digit in Keypad.FIRST_DIGIT..Keypad.LAST_DIGIT) {
            val run = Keypad.symbolsOn(digit)
            assertTrue(run.length <= 4, "key $digit carries ${run.length} marks")
        }
    }

    @Test
    fun `the marks a password and an address need are one tap`() {
        // The reason for the ordering inside each group. If these drift the layer still works,
        // but the two use cases that motivated it get slower.
        assertEquals('@', Keypad.symbolsOn('4').first())
        assertEquals('!', Keypad.symbolsOn('3').first())
        assertEquals('-', Keypad.symbolsOn('8').first())
        assertEquals('.', Keypad.symbolsOn('2').first())
    }

    @Test
    fun `a key outside the letter range carries no marks`() {
        assertEquals("", Keypad.symbolsOn('0'))
        assertEquals("", Keypad.symbolsOn('1'))
    }

    @Test
    fun `no mark can reach the dictionary`() {
        // The layer commits straight into the field and never through the engine, and this is the
        // guard that says so from the other end: a word carrying a mark has no key sequence, so
        // it cannot be learnt or offered even if something did put one there.
        for (mark in qwerty) {
            assertNull(
                Keypad.sequenceOf("ja${mark}n"),
                "$mark must leave a word unreachable by digits, or it becomes a candidate",
            )
        }
    }

    @Test
    fun `the letter layer is untouched by any of this`() {
        assertEquals("abcąć", Keypad.lettersOn('2'))
        assertEquals('2', Keypad.digitOf('ć'))
    }
}
