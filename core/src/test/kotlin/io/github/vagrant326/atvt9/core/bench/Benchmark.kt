package io.github.vagrant326.atvt9.core.bench

import io.github.vagrant326.atvt9.core.Cost
import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.Simulator
import io.github.vagrant326.atvt9.core.UserDictionary
import java.io.File

/**
 * KSPC over the query corpus, cold and warm.
 *
 * Two figures because T9 has two, and quoting either alone misleads. **Cold** is the keyboard on
 * the day it is installed, where a series title costs its full multitap price. **Warm** is the
 * same queries after the user dictionary has seen them once, which is the state the keyboard
 * spends its life in. The published 1.0072 corresponds to neither: it assumes every word is in
 * the dictionary, over running English prose, which is not what anyone types into a television.
 */
fun main(arguments: Array<String>) {
    val options = arguments.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val queries = File(options["--queries"] ?: "bench/queries-v1.tsv")
    if (!queries.exists()) {
        System.err.println("no query corpus at ${queries.absolutePath}")
        return
    }

    val dictionaries = listOf("pl", "en").associateWith { language ->
        options["--dictionary-$language"]
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.inputStream()
            ?.use { Dictionary.read(it) }
    }
    for ((language, dictionary) in dictionaries) {
        println(
            if (dictionary == null) {
                "$language: no dictionary — run corpus/build.py"
            } else {
                "$language: ${dictionary.wordCount} words"
            }
        )
    }

    val rows = queries.readLines()
        .drop(1)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t') }
        .filter { it.size >= 2 }

    println()
    println("%-34s %-6s %8s %8s".format("query", "lang", "cold", "warm"))
    println("-".repeat(60))

    var cold = Cost.ZERO
    var warm = Cost.ZERO

    for (row in rows) {
        val query = row[0].trim()
        val tag = row[1].trim()
        // A mixed query is measured against the language the user would have had selected, and
        // Polish is the one this household types in. Recorded here rather than averaged away.
        val language = if (tag == "en") "en" else "pl"
        val dictionary = dictionaries[language]

        // A fresh user dictionary per query: the cold figure is the first time this query has
        // ever been typed, not the first time after twenty others taught the keyboard their
        // words.
        val coldCost = Simulator(dictionary, UserDictionary()).cost(query)
        val warmCost = Simulator(dictionary, UserDictionary()).warmed(query)
        cold += coldCost
        warm += warmCost

        println(
            "%-34s %-6s %8.4f %8.4f".format(
                query.take(34),
                language,
                coldCost.kspc,
                warmCost.kspc,
            )
        )
    }

    println("-".repeat(60))
    println("%-34s %-6s %8.4f %8.4f".format("ALL", "", cold.kspc, warm.kspc))
    println()
    println(
        "cold: %d of %d words in a dictionary, %d needed NEXT, %d were spelled out"
            .format(
                cold.wordsMatched,
                cold.words,
                cold.wordsMatched - cold.wordsFirstChoice,
                cold.spelledWords,
            )
    )
    println(
        "warm: %d of %d words in a dictionary, %d needed NEXT, %d were spelled out"
            .format(
                warm.wordsMatched,
                warm.words,
                warm.wordsMatched - warm.wordsFirstChoice,
                warm.spelledWords,
            )
    )
    println()
    println("Baselines from docs/00-overview.md §5: multitap 2.0342, LetterWise 1.1500,")
    println("published T9 1.0072 (dictionary words only, running prose, not this workload).")
}
