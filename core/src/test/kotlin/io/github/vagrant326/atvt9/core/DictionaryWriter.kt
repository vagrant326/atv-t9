package io.github.vagrant326.atvt9.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.ln
import kotlin.math.min

/**
 * Writes the dictionary format, for tests only.
 *
 * A second implementation of a format is normally a liability, and here it is the point: this
 * one exists so [Dictionary] can be tested against words chosen by the test rather than against
 * whatever `corpus/build.py` last produced. The encoder that actually ships the assets is the
 * Python one, and `DictionaryTest.shippedDictionariesRead` is what keeps the two in agreement —
 * that test reads the committed asset, so a change to either side that the other did not follow
 * fails the build rather than the keyboard.
 */
object DictionaryWriter {

    private const val INDEX_STEP = 32
    private const val ESCAPE = 0xFF

    fun write(words: List<Pair<String, Int>>): ByteArray {
        val ordered = words.sortedWith(
            compareBy<Pair<String, Int>> { Keypad.sequenceOf(it.first) }
                .thenByDescending { it.second }
                .thenBy { it.first }
        )
        val alphabet = ordered.flatMap { it.first.toList() }.distinct().sorted()
        val indexOf = alphabet.withIndex().associate { (position, letter) -> letter to position + 1 }
        val highest = ordered.maxOfOrNull { it.second } ?: 1
        val scale = ln(1.0 + highest).takeIf { it > 0.0 } ?: 1.0

        val entries = ByteArrayOutputStream()
        val checkpoints = ArrayList<Int>()
        var previous = ""

        for ((position, entry) in ordered.withIndex()) {
            val (word, count) = entry
            val shared = if (position % INDEX_STEP == 0) {
                checkpoints.add(entries.size())
                0
            } else {
                var common = 0
                val limit = min(previous.length, word.length)
                while (common < limit && previous[common] == word[common]) {
                    common++
                }
                common
            }

            val suffix = word.substring(shared)
            if (shared >= 15 || suffix.length >= 15) {
                entries.write(ESCAPE)
                entries.write(shared)
                entries.write(suffix.length)
            } else {
                entries.write((shared shl 4) or suffix.length)
            }
            for (letter in suffix) {
                entries.write(indexOf.getValue(letter))
            }
            entries.write(maxOf(1, min(255, 1 + (254 * ln(1.0 + count) / scale).toInt())))
            previous = word
        }

        val body = entries.toByteArray()
        val out = ByteArrayOutputStream()
        val stream = DataOutputStream(out)
        stream.write(Dictionary.MAGIC.encodeToByteArray())
        stream.writeByte(Dictionary.VERSION)
        stream.writeByte(alphabet.size)
        for (letter in alphabet) {
            stream.writeChar(letter.code)
        }
        stream.writeInt(ordered.size)
        stream.writeShort(INDEX_STEP)
        stream.writeInt(checkpoints.size)
        for (offset in checkpoints) {
            stream.writeInt(offset)
        }
        stream.writeInt(body.size)
        stream.write(body)
        stream.flush()
        return out.toByteArray()
    }

    fun of(vararg words: Pair<String, Int>): Dictionary =
        Dictionary.read(write(words.toList()).inputStream())
}
