package com.piercingxx.xxphone.ui

/**
 * T9 match-as-you-type for the keypad (design §12.2): a query of keypad
 * digits matches a contact either by digit run inside its number, or through
 * the letters of its display name mapped onto the same digits. Pure JVM.
 */
object T9 {

    /** `2`→ABC … `9`→WXYZ; digits pass through, everything else drops. */
    private val LETTER_TO_DIGIT = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
    )

    fun encode(text: String): String = buildString {
        for (ch in text.lowercase()) {
            when {
                ch in '0'..'9' -> append(ch)
                else -> LETTER_TO_DIGIT[ch]?.let(::append)
            }
        }
    }

    fun digitsOf(number: String): String = number.filter { it in '0'..'9' }

    sealed interface Hit {
        /** Matched [len] digits starting at digit index [digitStart]; the UI maps these back onto the raw string. */
        data class InNumber(val digitStart: Int, val len: Int) : Hit

        /** The name's T9 encoding starts with the query; highlight its first [prefixLen] letters. */
        data class InName(val prefixLen: Int) : Hit
    }

    fun hit(query: String, number: String, displayName: String): Hit? {
        val q = encode(query)
        if (q.isEmpty()) return null
        val digits = digitsOf(number)
        val idx = digits.indexOf(q)
        if (idx >= 0) return Hit.InNumber(idx, q.length)
        val nameT9 = encode(displayName)
        if (nameT9.startsWith(q)) return Hit.InName(q.length)
        return null
    }

    /**
     * Maps an [InNumber] hit back onto the raw (formatted) number string:
     * returns the inclusive/exclusive char range covering the matched digits,
     * or null if the raw text changed shape since the hit was computed.
     */
    fun charRange(rawNumber: String, hit: Hit.InNumber): IntRange? {
        var seen = 0
        var start = -1
        var end = -1
        rawNumber.forEachIndexed { i, ch ->
            if (ch !in '0'..'9') return@forEachIndexed
            if (seen == hit.digitStart) start = i
            if (seen >= hit.digitStart && seen < hit.digitStart + hit.len) end = i
            seen++
        }
        return if (start >= 0 && end >= start &&
            seen - hit.digitStart >= hit.len
        ) start..end else null
    }
}

/**
 * Deterministic monogram initials (design §12.1): up to two leading letters
 * of the display words, uppercase; no photos under Contact Scopes — the
 * avatar is always this. Blank names degrade to "?".
 */
object Monograms {

    /**
     * ONE monogram rule for every avatar surface (Recents strip, People
     * rows): letters-only words, two leading initials, a single-word name
     * keeps its first two letters, and blank yields "#".
     */
    fun initials(displayName: String?): String {
        val words = displayName?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()
        val letters = words.filter { it.first().isLetter() }
        return when {
            letters.isEmpty() -> "#"
            letters.size == 1 -> letters[0].take(2).uppercase()
            else -> (letters[0].first().toString() + letters[1].first()).uppercase()
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
