package com.piercingxx.xxphone.ui

import com.piercingxx.xxphone.core.PatternRule

/**
 * Pure seam behind the Rules mask builder (§8, §12.4): typed digits in,
 * (prefix, wildcards) out — the exact contract core.[PatternRule] enforces
 * (prefix excludes '+', digits only; length-exact matching). The activity
 * feeds this E.164 digits when the sample normalizes, dialed digits when it
 * doesn't; either way everything below stays plain-string and JVM-testable.
 */
internal object MaskBuilder {

    const val WILDCARD = 'X'

    data class Draft(val prefix: String, val wildcards: Int) {
        val totalLength: Int get() = prefix.length + wildcards
    }

    /** Digit skeleton of whatever the user typed ('+' and formatting dropped). */
    fun digitsOf(typed: String): String = typed.filter(Char::isDigit)

    /**
     * Mask [maskedTail] trailing digits into X's. Needs at least one prefix
     * digit (PatternRule forbids an empty prefix); maskedTail 0 is a legal
     * exact-number rule.
     */
    fun build(digits: String, maskedTail: Int): Draft? {
        if (maskedTail < 0 || maskedTail >= digits.length) return null
        val prefix = digits.dropLast(maskedTail)
        if (prefix.isEmpty() || prefix.any { it !in '0'..'9' }) return null
        return Draft(prefix, maskedTail)
    }

    /**
     * Call Control-style preview: NANP shapes group as 425-555-XXXX
     * (+1-prefixed for 11-digit E164); anything else prints +digits with X's.
     */
    fun preview(draft: Draft): String {
        val full = draft.prefix + WILDCARD.toString().repeat(draft.wildcards)
        return when {
            full.length == 11 && full.startsWith("1") ->
                "+1 ${full.substring(1, 4)}-${full.substring(4, 7)}-${full.substring(7)}"
            full.length == 10 ->
                "${full.substring(0, 3)}-${full.substring(3, 6)}-${full.substring(6)}"
            else -> "+$full"
        }
    }

    /**
     * Neighbor-spoof preset derivation (§8, D5): the user's own six-digit
     * prefix over the FULL normalized E164 digit string — country code kept,
     * because core.PatternRule matching is length-exact against exactly those
     * digits and a local-part prefix would never fire on a live call.
     * Silence, never block.
     */
    fun neighborDraft(e164Digits: String): Draft? {
        if (e164Digits.isEmpty() || e164Digits.any { it !in '0'..'9' }) return null
        if (e164Digits.length < 7) return null // need ≥1 wildcard tail
        return build(e164Digits, e164Digits.length - 6)
    }
}
