package io.github.vagrant326.atvt9.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DictionaryTest {

    @Test
    fun `exact matches come back in count order`() {
        val dictionary = DictionaryWriter.of(
            "los" to 900_000,
            "kos" to 4_000,
            "kop" to 300,
        )
        // 5-6-7 spells all three. The commonest has to be first or every press of NEXT that
        // follows is one the ranking could have saved.
        val words = dictionary.candidates("567").filter { it.exact }.map { it.word }
        assertEquals(listOf("los", "kos", "kop"), words)
    }

    @Test
    fun `Polish diacritics are reachable without being typed`() {
        val dictionary = DictionaryWriter.of("zle" to 10, "źle" to 900, "wiedźmin" to 500)

        // 9-5-3 is z-l-e on the keypad. The user never asks for the diacritic; the dictionary
        // restores it, which is the entire argument for T9 in Polish.
        assertEquals("źle", dictionary.candidates("953").first().word)
        assertTrue(dictionary.candidates("94339646").any { it.word == "wiedźmin" })
    }

    @Test
    fun `a prefix offers completions after the words it spells`() {
        val dictionary = DictionaryWriter.of("kot" to 100, "kotek" to 90, "kotlet" to 80)
        val found = dictionary.candidates("568")

        assertEquals("kot", found.first().word)
        assertTrue(found.first().exact)
        assertTrue(found.drop(1).none { it.exact })
        assertTrue(found.map { it.word }.containsAll(listOf("kotek", "kotlet")))
    }

    @Test
    fun `words are found across checkpoint boundaries`() {
        // More than one index step, so the binary search has to land in a block and walk back
        // into the one before it. That backward step is the part most likely to be wrong.
        val letters = "abcdefg"
        val words = buildList {
            for (first in letters) {
                for (second in letters) {
                    for (third in letters) {
                        add("$first$second$third")
                    }
                }
            }
        }.mapIndexed { index, word -> word to (1000 - index) }
        val dictionary = Dictionary.read(DictionaryWriter.write(words).inputStream())

        for ((word, _) in words) {
            assertTrue(dictionary.contains(word), "lost $word")
        }
        assertFalse(dictionary.contains("nigdytakiegoniebylo"))
    }

    @Test
    fun `long words survive the escape encoding`() {
        val long = "najniepieprzniejszy"
        val dictionary = DictionaryWriter.of(long to 5, "${long}mi" to 4, "a" to 900)

        assertTrue(dictionary.contains(long))
        assertTrue(dictionary.contains("${long}mi"))
    }

    /**
     * Reads the assets the app actually ships.
     *
     * The Python encoder and the Kotlin reader are two implementations of one format, and this is
     * the only test that puts them in the same room. Skipped rather than failed when the file is
     * absent, because a fresh clone has no dictionary until somebody runs `corpus/build.py` —
     * but on CI the assets are committed, so it runs.
     */
    @Test
    fun `shipped dictionaries read`() {
        for (language in listOf("pl", "en")) {
            // Tests run with the module directory as the working directory, so the asset is one
            // level up. Both spellings are tried rather than assuming which runner is in use.
            val asset = listOf(
                File("../app/src/main/assets/dictionary-$language.bin"),
                File("app/src/main/assets/dictionary-$language.bin"),
            ).firstOrNull { it.exists() } ?: continue
            val dictionary = asset.inputStream().use { Dictionary.read(it) }
            assertTrue(dictionary.wordCount > 1000, "$language looks empty")

            val probe = if (language == "pl") "nie" else "the"
            val digits = Keypad.sequenceOf(probe)!!
            assertTrue(
                dictionary.candidates(digits).any { it.word == probe },
                "$language does not contain $probe",
            )
        }
    }
}
