package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class UserDictionaryTest {

    @Test
    fun `a learnt word is offered for its sequence`() {
        val user = UserDictionary()
        assertTrue(user.learn("wiedźmin"))

        val found = user.candidates("94339646")
        assertEquals(listOf("wiedźmin"), found.map { it.word })
        assertTrue(found.single().exact)
    }

    @Test
    fun `repeated use raises a word above one used once`() {
        val user = UserDictionary()
        user.learn("kot")
        repeat(5) { user.learn("los") }

        // Both are 5-6-7. The one actually used has to come first.
        assertEquals("los", user.candidates("567").first().word)
    }

    @Test
    fun `words the keypad cannot spell are refused`() {
        val user = UserDictionary()
        assertFalse(user.learn("don't"))
        assertEquals(0, user.size)
    }

    @Test
    fun `case is folded so one word is not stored twice`() {
        val user = UserDictionary()
        user.learn("Wiedźmin")
        user.learn("wiedźmin")

        assertEquals(1, user.size)
        assertEquals(2, user.usesOf("wiedźmin"))
    }

    @Test
    fun `the store round-trips through a file`() {
        val original = UserDictionary()
        original.learn("wiedźmin")
        repeat(3) { original.learn("żmijowisko") }

        val bytes = ByteArrayOutputStream().also { original.write(it) }.toByteArray()
        val restored = UserDictionary.read(bytes.inputStream())

        assertEquals(2, restored.size)
        assertEquals(3, restored.usesOf("żmijowisko"))
        assertFalse(restored.isDirty, "a store just read from disk has nothing to save")
    }

    @Test
    fun `the least used word goes when the store is full`() {
        val user = UserDictionary(capacity = 3)
        repeat(4) { user.learn("czesto") }
        repeat(2) { user.learn("rzadziej") }
        user.learn("raz")
        user.learn("nowe")

        assertEquals(3, user.size)
        assertFalse(user.contains("raz"), "the single use should have been the one dropped")
        assertTrue(user.contains("czesto"))
    }

    @Test
    fun `forgetting a word removes it from its sequence too`() {
        val user = UserDictionary()
        user.learn("kot")
        assertTrue(user.forget("kot"))

        assertEquals(0, user.candidates("568").size)
        assertFalse(user.forget("kot"), "removing what is not there is not a change")
    }
}
