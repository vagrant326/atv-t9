package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MultitapTest {

    @Test
    fun `tapping one key cycles its letters`() {
        val multitap = Multitap()
        multitap.press('2', 0)
        assertEquals("a", multitap.text())
        multitap.press('2', 100)
        assertEquals("b", multitap.text())
        multitap.press('2', 200)
        assertEquals("c", multitap.text())
    }

    @Test
    fun `the Polish letters are at the end of their key`() {
        val multitap = Multitap()
        repeat(5) { multitap.press('9', it * 100L) }
        assertEquals("ź", multitap.text(), "w x y z ź")
    }

    @Test
    fun `the cycle closes rather than sticking on the last letter`() {
        val multitap = Multitap()
        repeat(4) { multitap.press('4', it * 100L) }
        assertEquals("g", multitap.text(), "g h i then back to g")
    }

    @Test
    fun `a different key starts a new letter`() {
        val multitap = Multitap()
        multitap.press('5', 0)
        multitap.press('6', 50)
        assertEquals("jm", multitap.text())
    }

    @Test
    fun `the timeout is what makes a doubled letter reachable`() {
        val multitap = Multitap(timeoutMillis = 500)
        multitap.press('2', 0)
        multitap.press('2', 900)
        assertEquals("aa", multitap.text())
    }

    @Test
    fun `settling ends the letter without waiting for the clock`() {
        val multitap = Multitap()
        multitap.press('2', 0)
        multitap.settle()
        multitap.press('2', 10)
        assertEquals("aa", multitap.text())
    }

    @Test
    fun `backspace drops a whole letter, not a tap`() {
        val multitap = Multitap()
        multitap.press('2', 0)
        multitap.press('2', 50)
        multitap.press('3', 100)
        multitap.backspace()
        assertEquals("b", multitap.text())
    }
}
