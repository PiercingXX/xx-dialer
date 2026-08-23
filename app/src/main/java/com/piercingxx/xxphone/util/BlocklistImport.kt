package com.piercingxx.xxphone.util

/**
 * Offline blocklist import parsing (§8): the user picks a text file, one
 * number per line; everything stays on-device (R8). This seam is pure —
 * normalize or count, no I/O, no Android — so the JVM suite owns the
 * messy-file cases; RulesActivity owns the BlockedNumberContract writes (D6)
 * and the honest role-lost degradation.
 */
object BlocklistImport {

    /** Survivors ready for BlockedNumberContract, plus how many lines did not survive. */
    data class Parsed(val numbers: List<String>, val skipped: Int)

    /**
     * Lines → normalized E.164 numbers. Blank lines and `#` comments are file
     * structure, not garbage: they never count as skipped. A content line that
     * fails normalization (unparseable or invalid) increments [Parsed.skipped].
     */
    fun parse(text: String, defaultRegion: String = "US"): Parsed {
        var skipped = 0
        val numbers = mutableListOf<String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val e164 = runCatching { E164.normalize(line, defaultRegion) }.getOrNull()
                if (e164 == null) skipped++ else numbers += e164
            }
        return Parsed(numbers, skipped)
    }
}
