package com.piercingxx.xxdialer.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §8 pattern rules: normalized digit prefix + trailing wildcard count. No regex anywhere. */
class PatternRuleTest {

    // "425-555-XXXX" in builder terms.
    private val rule = PatternRule(prefix = "1425555", wildcardCount = 4)

    @Test
    fun exactLength_exactTail_matches() {
        assertTrue(rule.matches("+14255550100"))
    }

    @Test
    fun wildcardTail_anySuffixDigits_match() {
        assertTrue(rule.matches("+14255550001"))
        assertTrue(rule.matches("+14255559999"))
    }

    @Test
    fun prefixMismatch_doesNotMatch() {
        assertFalse(rule.matches("+14255560100"))
        assertFalse(rule.matches("+12065550100"))
    }

    @Test
    fun longerNumber_samePrefix_doesNotMatch() {
        assertFalse(rule.matches("+142555501001"))
    }

    @Test
    fun shorterNumber_doesNotMatch() {
        assertFalse(rule.matches("+1425555100"))
    }

    @Test
    fun zeroWildcards_isExactMatch() {
        val exact = PatternRule(prefix = "14155550100", wildcardCount = 0)
        assertTrue(exact.matches("+14155550100"))
        assertFalse(exact.matches("+14155550101"))
    }
}
