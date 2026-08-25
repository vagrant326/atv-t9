package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class T9EngineTest {

    // los, kos and kop all spell 5-6-7, which is the case the whole method turns on.
    private val dictionary = DictionaryWriter.of(
        "los" to 900_000,
        "kos" to 4_000,
        "kop" to 300,
        "kot" to 5_000,
        "źle" to 900,
    )

    private fun engine(user: UserDictionary = UserDictionary()) = T9Engine(dictionary, user)

    private fun T9Engine.type(digits: String) = digits.forEach { press(it, atMillis = 0) }

    @Test
    fun `the commonest word is offered first`() {
        val engine = engine()
        engine.type("567")
        assertEquals("los", engine.composing)
        assertTrue(engine.hasMatch)
    }

    @Test
    fun `NEXT walks the candidates and wraps`() {
        val engine = engine()
        engine.type("567")
        val seen = buildList {
            add(engine.composing)
            repeat(engine.candidates.size - 1) {
                engine.next()
                add(engine.composing)
            }
        }
        assertEquals(listOf("los", "kos", "kop"), seen)

        engine.next()
        assertEquals("los", engine.composing, "the cycle has to close")
    }

    @Test
    fun `an unknown sequence reports no match rather than a wrong word`() {
        val engine = engine()
        engine.type("9999")
        assertFalse(engine.hasMatch)
        assertEquals("9999", engine.composing, "the sequence is the only honest thing to show")
    }

    @Test
    fun `a committed word is learnt and wins its sequence afterwards`() {
        val user = UserDictionary()
        val first = engine(user)
        first.spell()
        // w-i-e-d-ź-m-i-n by multitap: this is the once-per-word cost the design accepts.
        listOf('9' to 1, '4' to 3, '3' to 2, '3' to 1, '9' to 5, '6' to 1, '4' to 3, '6' to 2)
            .forEach { (digit, taps) ->
                repeat(taps) { first.press(digit, atMillis = 0) }
                first.settle()
            }
        assertEquals("wiedźmin", first.composing)
        assertEquals("wiedźmin", first.commit())

        val second = engine(user)
        second.type("94339646")
        assertEquals("wiedźmin", second.composing, "the second time has to cost eight presses")
    }

    @Test
    fun `a used word outranks the shipped vocabulary it collides with`() {
        val user = UserDictionary()

        // One use is not a mandate. `los` is the commonest word on this sequence by two orders
        // of magnitude, and a single stray commit must not be able to displace it.
        user.learn("kop")
        engine(user).let { engine ->
            engine.type("567")
            assertEquals("los", engine.composing)
            assertEquals("kop", engine.candidates[1].word, "but it has to climb past the rest")
        }

        // By the third the user has been clearer than the corpus can be.
        repeat(2) { user.learn("kop") }
        engine(user).let { engine ->
            engine.type("567")
            assertEquals("kop", engine.composing)
        }
    }

    @Test
    fun `learning can be refused without disturbing the commit`() {
        val user = UserDictionary()
        val engine = engine(user)
        engine.type("568")

        assertEquals("kot", engine.commit(learn = false))
        assertEquals(0, user.size, "a password field must leave nothing behind")
    }

    @Test
    fun `spelling discards the pending sequence rather than reinterpreting it`() {
        val engine = engine()
        engine.type("643")
        engine.spell()

        assertFalse(engine.isComposing)
        assertEquals(Composer.SPELL, engine.mode)
        assertNull(engine.commit())
    }

    @Test
    fun `backspace walks back through the sequence`() {
        val engine = engine()
        engine.type("5677")
        assertFalse(engine.hasMatch)

        assertTrue(engine.backspace())
        assertEquals("los", engine.composing)

        repeat(3) { engine.backspace() }
        assertFalse(engine.isComposing)
        assertFalse(engine.backspace(), "nothing pending, so nothing to report")
    }
}
