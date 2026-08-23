package com.piercingxx.xxphone.core

import java.time.LocalDateTime

enum class HiddenCallerPolicy { UNKNOWN, SILENCE, BLOCK }

enum class StirAction { BLOCK, SILENCE, OFF }

/**
 * A digit-mask rule in the Call Control builder style (§8): a normalized E.164
 * digit prefix plus a count of masked trailing digits — `425-555-XXXX` is
 * prefix `1425555`, wildcards `4`. Deliberately no regex.
 */
data class PatternRule(
    val prefix: String,       // E.164 digits only, no leading '+'
    val wildcardCount: Int,
) {
    init {
        require(prefix.isNotEmpty()) { "prefix must not be empty" }
        require(prefix.all { it in '0'..'9' }) { "prefix must be normalized digits: $prefix" }
        require(wildcardCount >= 0) { "wildcardCount must be >= 0" }
    }

    /** Length-exact: a masked-tail rule must not swallow longer numbers. */
    fun matches(e164Number: String): Boolean {
        val digits = e164Number.removePrefix("+")
        return digits.length == prefix.length + wildcardCount && digits.startsWith(prefix)
    }
}

data class Rules(
    val unknownWindow: Window = Window(9 * 60, 17 * 60, Window.ALL_DAYS),
    val businessWindow: Window = Window(9 * 60, 19 * 60, Window.ALL_DAYS),
    val hiddenCallerPolicy: HiddenCallerPolicy = HiddenCallerPolicy.UNKNOWN,
    val stirAction: StirAction = StirAction.BLOCK,
    val repeatCallerEnabled: Boolean = true,
    val bypassUntil: LocalDateTime? = null,   // Expecting-a-call expiry; null = inactive
    val blockPatterns: List<PatternRule> = emptyList(),
    val silencePatterns: List<PatternRule> = emptyList(),
)
