package io.github.vagrant326.atvt9.core

import java.io.InputStream

/**
 * One word the dictionary offers for a key sequence.
 *
 * [exact] separates the words the sequence spells from the words it merely starts. Both belong
 * on the strip, but never mixed: a user who has typed four keys wants the four-letter words
 * first, and an eight-letter completion that happens to score higher is not a better answer to
 * what they typed.
 */
data class Candidate(val word: String, val score: Int, val exact: Boolean)

/**
 * The shipped dictionary for one language, as built by `corpus/build.py`.
 *
 * **Sorted by key sequence, not alphabetically.** That single decision is what makes the format
 * work: every word a sequence can produce is contiguous, and so is every word it can complete
 * to, so both questions T9 asks are a binary search and a short forward scan. Sorted
 * alphabetically the same file would need a separate digit index roughly its own size again.
 *
 * Held as one byte array rather than memory-mapped. Assets are stored compressed inside the
 * APK, so there is nothing to map — the file has to be inflated somewhere regardless, and the
 * only choice is whether a copy also gets decoded into objects. It does not: words are decoded
 * on demand during a scan and only the handful that reach the strip survive the call.
 *
 * Format, big-endian:
 *
 *     magic        4 bytes  "T9D1"
 *     version      u8       1
 *     alphabetLen  u8       N, at most 63
 *     alphabet     N x u16  UTF-16 code units; byte value i+1 encodes alphabet[i]
 *     wordCount    u32
 *     indexStep    u16      words between checkpoints
 *     indexLen     u32
 *     index        indexLen x u32   offset of each checkpoint, from the start of entries
 *     entriesLen   u32
 *     entries      entriesLen bytes
 *
 * One entry, with words in (sequence ascending, score descending) order:
 *
 *     b0           u8       high nibble shared prefix, low nibble suffix length
 *                           0xFF escapes to u8 shared, u8 suffixLen for the rare long word
 *     suffix       suffixLen bytes, each an alphabet index plus one
 *     score        u8       log-scaled frequency, 255 most frequent
 *
 * A checkpoint word always stores a shared prefix of zero, which is what lets a search start
 * there without having decoded everything before it.
 */
class Dictionary private constructor(
    private val bytes: ByteArray,
    private val alphabet: CharArray,
    val wordCount: Int,
    val indexStep: Int,
    private val indexAt: IntArray,
    private val entriesAt: Int,
) {

    /**
     * Words for [digits], best first: everything the sequence spells, then what it completes to.
     *
     * Completions are capped by [COMPLETION_SCAN] rather than by correctness. A two-key prefix
     * covers a fifth of the dictionary and scanning all of it to rank ten words would be work
     * done for a strip nobody reads that far along. The cap costs a rare good completion behind
     * a very short prefix, which is the cheapest thing here to be wrong about.
     */
    fun candidates(digits: String, limit: Int = DEFAULT_LIMIT): List<Candidate> {
        if (digits.isEmpty() || limit <= 0) {
            return emptyList()
        }

        val cursor = seek(digits) ?: return emptyList()
        val exact = ArrayList<Candidate>(limit)
        val completions = ArrayList<Candidate>()
        var scanned = 0

        while (cursor.advance()) {
            val sequence = cursor.sequence()
            if (!sequence.startsWith(digits)) {
                break
            }
            if (sequence.length == digits.length) {
                // Stored score-descending inside one sequence, so the first are already the best
                // and the rest of the bucket cannot improve on a full strip.
                if (exact.size < limit) {
                    exact.add(Candidate(cursor.word(), cursor.score(), exact = true))
                }
            } else {
                completions.add(Candidate(cursor.word(), cursor.score(), exact = false))
                if (++scanned >= COMPLETION_SCAN) {
                    break
                }
            }
        }

        if (exact.size >= limit) {
            return exact
        }
        completions.sortByDescending { it.score }
        return exact + completions.take(limit - exact.size)
    }

    /** Whether the dictionary already holds [word], which is what stops the user layer growing a
     *  second copy of every common word the moment somebody types one. */
    fun contains(word: String): Boolean {
        val digits = Keypad.sequenceOf(word) ?: return false
        val cursor = seek(digits) ?: return false
        while (cursor.advance()) {
            val sequence = cursor.sequence()
            if (sequence.length != digits.length || !sequence.startsWith(digits)) {
                return false
            }
            if (cursor.word() == word) {
                return true
            }
        }
        return false
    }

    /**
     * Positions a cursor just before the first word whose sequence is at or after [digits].
     *
     * The binary search runs over checkpoints, then backs up one and walks. Backing up is not
     * defensive: the checkpoint found is the first at or after the target, and the words the
     * target actually needs may sit in the block before it.
     */
    private fun seek(digits: String): Cursor? {
        if (indexAt.isEmpty()) {
            return null
        }
        var low = 0
        var high = indexAt.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (Cursor(indexAt[middle]).run { advance(); sequence() } < digits) {
                low = middle + 1
            } else {
                high = middle
            }
        }

        val cursor = Cursor(indexAt[if (low == 0) 0 else low - 1])
        while (cursor.advance()) {
            if (cursor.sequence() >= digits) {
                return cursor.rewound()
            }
        }
        return null
    }

    /**
     * Walks entries from a checkpoint, carrying the previous word so front coding resolves.
     *
     * A cursor is deliberately mutable and single-use. Decoding one word costs a substring and a
     * StringBuilder, and a scan touches thousands, so a design that allocated a result object
     * per step would put the keyboard's per-keystroke work in the garbage collector.
     */
    private inner class Cursor(private val start: Int) {

        private var at = start
        private var previous = ""
        private var word = ""
        private var score = 0
        private var beforeLast = start

        fun advance(): Boolean {
            if (at >= bytes.size - entriesAt) {
                return false
            }
            beforeLast = at
            previous = word

            var offset = entriesAt + at
            val head = bytes[offset].toInt() and 0xFF
            val shared: Int
            val suffixLength: Int
            if (head == ESCAPE) {
                shared = bytes[offset + 1].toInt() and 0xFF
                suffixLength = bytes[offset + 2].toInt() and 0xFF
                offset += 3
            } else {
                shared = head ushr 4
                suffixLength = head and 0x0F
                offset += 1
            }

            val builder = StringBuilder(shared + suffixLength)
            builder.append(previous, 0, shared)
            for (step in 0 until suffixLength) {
                builder.append(alphabet[(bytes[offset + step].toInt() and 0xFF) - 1])
            }
            word = builder.toString()
            score = bytes[offset + suffixLength].toInt() and 0xFF
            at = offset + suffixLength + 1 - entriesAt
            return true
        }

        /**
         * Steps back one entry, so the caller can `advance()` onto the word just inspected.
         *
         * The new cursor is seeded with [previous] as its *word*, not as its previous: the first
         * thing `advance` does is shift word into previous, and the entry being replayed front
         * codes against exactly that.
         */
        fun rewound(): Cursor = Cursor(beforeLast).also { it.word = previous }

        fun word(): String = word

        fun score(): Int = score

        fun sequence(): String = Keypad.sequenceOf(word).orEmpty()
    }

    companion object {

        const val MAGIC = "T9D1"
        const val VERSION = 1
        const val DEFAULT_LIMIT = 8

        private const val ESCAPE = 0xFF
        private const val COMPLETION_SCAN = 4096

        fun read(input: InputStream): Dictionary {
            val bytes = input.readBytes()
            require(bytes.size > 16) { "dictionary is truncated: ${bytes.size} bytes" }
            require(String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) {
                "not a dictionary: magic was ${String(bytes, 0, 4, Charsets.US_ASCII)}"
            }
            require(bytes[4].toInt() == VERSION) { "dictionary version ${bytes[4]} is not $VERSION" }

            var at = 5
            val alphabetLength = bytes[at++].toInt() and 0xFF
            val alphabet = CharArray(alphabetLength)
            for (index in 0 until alphabetLength) {
                alphabet[index] = readShort(bytes, at).toChar()
                at += 2
            }

            val wordCount = readInt(bytes, at); at += 4
            val indexStep = readShort(bytes, at); at += 2
            val indexLength = readInt(bytes, at); at += 4
            val index = IntArray(indexLength)
            for (checkpoint in 0 until indexLength) {
                index[checkpoint] = readInt(bytes, at)
                at += 4
            }
            at += 4 // entriesLen, implied by what is left

            return Dictionary(bytes, alphabet, wordCount, indexStep, index, at)
        }

        private fun readShort(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        private fun readInt(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
    }
}
