package io.github.vagrant326.atvt9.model

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import io.github.vagrant326.atvt9.R
import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.UserDictionary
import java.io.File

/**
 * [label] is the two-letter tag shown on the strip, where space is scarce; [titleRes] is the
 * language's own name, for the settings list. Names of languages are not translated — Polski is
 * Polski in every locale.
 *
 * Adding a language is one entry here plus one asset: `dictionary-<code>.bin`, built by
 * `corpus/build.py`. Nothing else in the app knows how many languages there are.
 */
enum class Language(
    val code: String,
    val label: String,
    @StringRes val titleRes: Int,
) {
    PL("pl", "PL", R.string.language_pl),
    EN("en", "EN", R.string.language_en),
}

/**
 * Loads the shipped dictionaries, lazily and once each.
 *
 * Lazily because a dictionary is the largest thing this app touches and the user has at most
 * one language active at a time; loading both at startup would double the keyboard's resident
 * footprint to no purpose. Once each because the alternative — reloading per input session — is
 * a file read on every tap of a search box.
 *
 * A missing or unreadable asset is not fatal. The keyboard falls back to spelling only, which
 * still types, and the strip says so rather than silently offering nothing.
 */
class DictionaryRepository(private val context: Context) {

    private val loaded = HashMap<Language, Dictionary?>()

    fun dictionaryFor(language: Language): Dictionary? = loaded.getOrPut(language) {
        val name = "dictionary-${language.code}.bin"
        runCatching {
            context.assets.open(name).use { Dictionary.read(it) }
        }.getOrElse { failure ->
            Log.w(TAG, "no usable dictionary in $name, spelling only", failure)
            null
        }
    }

    fun isTrained(language: Language): Boolean = dictionaryFor(language) != null

    fun wordCount(language: Language): Int = dictionaryFor(language)?.wordCount ?: 0

    private companion object {
        const val TAG = "T9"
    }
}

/**
 * The user's own words, on disk.
 *
 * One file, in the app's private storage, holding only words — never the text they appeared in,
 * never the field they were typed into, never when. That is the whole privacy design and it is
 * structural rather than a policy: there is nowhere in the format to put anything else.
 *
 * Written on a delay rather than on every word. A committed word is one file rewrite, a TV
 * query is a dozen words, and the store is small enough that rewriting it whole is cheaper than
 * any incremental scheme — but not so cheap that doing it twelve times per query is sensible.
 */
class UserWords(private val file: File) {

    val dictionary: UserDictionary by lazy {
        runCatching {
            if (file.exists()) file.inputStream().use { UserDictionary.read(it) } else null
        }.getOrElse { failure ->
            Log.w(TAG, "user words unreadable, starting empty", failure)
            null
        } ?: UserDictionary()
    }

    /**
     * Writes if anything changed. Safe to call often — a clean store costs one boolean.
     *
     * Writes through a temporary file and renames, because the alternative is a half-written
     * dictionary the next start cannot parse, and the recovery from that is silently discarding
     * every word the user ever taught the keyboard.
     */
    fun flush() {
        if (!dictionary.isDirty) {
            return
        }
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.outputStream().use { dictionary.write(it) }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }.onFailure { Log.w(TAG, "could not save user words", it) }
    }

    private companion object {
        const val TAG = "T9"
    }
}
