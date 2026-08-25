package io.github.vagrant326.atvt9.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The words the user added, and how often they have used them.
 *
 * This is not a nice-to-have layer over the shipped dictionary — it is the half that makes the
 * method usable here. A TV search box is mostly proper nouns, and a fixed dictionary answers a
 * series title with nothing at all, every time, for as long as the app is installed. Typing
 * `wiedźmin` once and having it there afterwards is the difference between a keyboard and a
 * demonstration.
 *
 * Separate from [Dictionary] and deliberately so. The shipped file is a minimised, front-coded
 * structure that cannot absorb a word without being rebuilt; a few thousand user words fit in a
 * hash map with room to spare, and the two are merged at lookup instead. Nothing here is
 * clever, because everything here has to survive being written on every committed word.
 *
 * **What must never reach this class is decided by the caller**, and the rule is in
 * `T9ImeService`: nothing typed into a password field, a no-suggestions field, or a field that
 * asked for no personalised learning. An adaptive keyboard that records indiscriminately is the
 * exact thing `docs/00-overview.md` §3.1 promises this project is not.
 *
 * Format, big-endian:
 *
 *     magic    4 bytes  "T9U1"
 *     version  u8       1
 *     count    u32
 *     entries  count x (u8 length, length bytes UTF-8, u32 uses)
 */
class UserDictionary(private val capacity: Int = DEFAULT_CAPACITY) {

    private val uses = HashMap<String, Int>()
    private val bySequence = HashMap<String, MutableList<String>>()

    var isDirty: Boolean = false
        private set

    val size: Int get() = uses.size

    /**
     * Records one use of [word], adding it if it is new. Returns false for anything the keypad
     * cannot spell, which is not a failure — a word with a digit or an apostrophe in it could
     * never be offered back, so storing it would only consume the budget.
     */
    fun learn(word: String): Boolean {
        val normalised = word.lowercase()
        val sequence = Keypad.sequenceOf(normalised) ?: return false

        val previous = uses[normalised]
        uses[normalised] = ((previous ?: 0) + 1).coerceAtMost(MAX_USES)
        if (previous == null) {
            bySequence.getOrPut(sequence) { ArrayList(2) }.add(normalised)
            if (uses.size > capacity) {
                evictLeastUsed()
            }
        }
        isDirty = true
        return true
    }

    fun forget(word: String): Boolean {
        val normalised = word.lowercase()
        if (uses.remove(normalised) == null) {
            return false
        }
        Keypad.sequenceOf(normalised)?.let { sequence ->
            bySequence[sequence]?.let { words ->
                words.remove(normalised)
                if (words.isEmpty()) {
                    bySequence.remove(sequence)
                }
            }
        }
        isDirty = true
        return true
    }

    fun contains(word: String): Boolean = word.lowercase() in uses

    fun usesOf(word: String): Int = uses[word.lowercase()] ?: 0

    fun words(): List<String> = uses.entries.sortedWith(
        compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
    ).map { it.key }

    /**
     * Words for [digits], scored so that a word the user has actually used outranks the shipped
     * vocabulary without a single use being enough to displace the commonest words in the
     * language.
     *
     * The first use lands just below the top of the shipped range, so `wiedźmin` beats the
     * obscure word it collides with immediately but does not jump ahead of `nie`. Each further
     * use climbs, and by the third the word wins its sequence outright. That curve is the whole
     * adaptation policy; there is no other model here.
     */
    fun candidates(digits: String, limit: Int = Dictionary.DEFAULT_LIMIT): List<Candidate> {
        if (digits.isEmpty() || limit <= 0) {
            return emptyList()
        }

        val found = ArrayList<Candidate>()
        bySequence[digits]?.forEach { word ->
            found.add(Candidate(word, scoreOf(word), exact = true))
        }
        if (found.size < limit) {
            for ((sequence, words) in bySequence) {
                if (sequence.length > digits.length && sequence.startsWith(digits)) {
                    words.forEach { found.add(Candidate(it, scoreOf(it), exact = false)) }
                }
            }
        }

        found.sortWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.score })
        return if (found.size <= limit) found else found.subList(0, limit).toList()
    }

    private fun scoreOf(word: String): Int =
        (FIRST_USE_SCORE + (uses[word] ?: 1) * PER_USE).coerceAtMost(255)

    /**
     * Drops the least used word once the store is full.
     *
     * A cap exists because this file is rewritten whenever it changes and read at every keyboard
     * start, and because an unbounded store of everything ever typed is precisely the artefact
     * §3.1 argues should not exist. Ties break on the word itself so eviction is deterministic
     * and a test can pin it.
     */
    private fun evictLeastUsed() {
        val victim = uses.entries.minWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }
        ) ?: return
        forget(victim.key)
    }

    fun write(output: OutputStream) {
        val stream = DataOutputStream(output.buffered())
        stream.write(MAGIC.encodeToByteArray())
        stream.writeByte(VERSION)
        stream.writeInt(uses.size)
        for ((word, count) in uses.entries.sortedBy { it.key }) {
            val encoded = word.encodeToByteArray()
            stream.writeByte(encoded.size)
            stream.write(encoded)
            stream.writeInt(count)
        }
        stream.flush()
        isDirty = false
    }

    companion object {

        const val MAGIC = "T9U1"
        const val VERSION = 1

        /**
         * Roughly a decade of series titles for one household. Chosen to be far above what any
         * real use produces, so the eviction path stays a backstop rather than a policy the user
         * would ever feel.
         */
        const val DEFAULT_CAPACITY = 4_000

        private const val MAX_USES = 1_000

        /**
         * The adaptation curve, and the only one in this project.
         *
         * One use scores 220, which clears most of the shipped vocabulary but not the handful
         * of words at the very top of a language — so a word typed once stops losing to
         * obscurities without displacing `nie`. Three uses saturate at 255 and win the sequence
         * outright, because by the third time the user has demonstrated which word they mean
         * more convincingly than any corpus can.
         *
         * Ties go to the user's word: the merge in [T9Engine] puts the user layer first.
         */
        private const val FIRST_USE_SCORE = 200
        private const val PER_USE = 20

        fun read(input: InputStream, capacity: Int = DEFAULT_CAPACITY): UserDictionary {
            val dictionary = UserDictionary(capacity)
            val stream = DataInputStream(input.buffered())
            val magic = ByteArray(4)
            stream.readFully(magic)
            require(magic.decodeToString() == MAGIC) {
                "not a user dictionary: magic was ${magic.decodeToString()}"
            }
            require(stream.readUnsignedByte() == VERSION) { "unknown user dictionary version" }

            repeat(stream.readInt()) {
                val encoded = ByteArray(stream.readUnsignedByte())
                stream.readFully(encoded)
                val word = encoded.decodeToString()
                val count = stream.readInt()
                Keypad.sequenceOf(word)?.let { sequence ->
                    dictionary.uses[word] = count
                    dictionary.bySequence.getOrPut(sequence) { ArrayList(2) }.add(word)
                }
            }
            dictionary.isDirty = false
            return dictionary
        }
    }
}
