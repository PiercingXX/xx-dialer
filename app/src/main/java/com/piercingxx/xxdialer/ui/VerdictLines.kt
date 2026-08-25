package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.core.PatternRule
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.Rules

/**
 * Renders the R7 verdict line for a Recents row (design §12.1):
 * `✓ rang` / `→ Silenced · Unknown, outside 09–17` / `✗ Blocked · pattern
 * 425-555-XXXX`. The screen_log row stores the Reason enum NAME, so dynamic
 * data (window bounds, pattern mask, weekday of the last outgoing call) is
 * substituted here from live rules — never from stale label text. Pure JVM.
 */
object VerdictLines {

    enum class Glyph { RING, SILENCED, BLOCKED, NONE }

    data class Line(val glyph: Glyph, val text: String)

    /**
     * [verdict] is `screen_log.verdict` (`Block`/`Ring`/`Silence`, or null
     * for platform-only rows); [reasonRaw] is `screen_log.reason` (Reason
     * enum name, may be blank). [blockedUpstream] marks CallLog BLOCKED rows
     * that never reached our screener (§4.4/§8). [recentOutgoingWeekday], when
     * derivable from loaded CallLog rows, replaces the static "Tue" skeleton.
     */
    fun annotate(
        e164: String?,
        verdict: String?,
        reasonRaw: String,
        observed: Boolean,
        rules: Rules,
        recentOutgoingWeekday: String? = null,
        blockedUpstream: Boolean = false,
    ): Line {
        val reason = toReason(reasonRaw)
        var line = when {
            verdict == "Block" -> blocked(e164, reason, rules)
            verdict == "Silence" -> silenced(e164, reason, rules)
            verdict == "Ring" -> rang(reason, recentOutgoingWeekday)
            blockedUpstream -> Line(Glyph.BLOCKED, "✗ Blocked · upstream setting")
            else -> return Line(Glyph.NONE, "")
        }
        // §6 observe mode is part of the record on every disposition.
        if (observed) line = line.copy(text = "${line.text} · observed")
        return line
    }

    private fun blocked(e164: String?, reason: Reason?, rules: Rules): Line {
        val detail = when (reason) {
            Reason.PATTERN_BLOCKED -> patternDetail(rules.blockPatterns, e164)
            Reason.STIR_FAILED -> "STIR failed"
            Reason.PATTERN_SILENCED -> patternDetail(rules.silencePatterns, e164)
            else -> "blocklist" // USER_BLOCKED and any unmapped fallback
        }
        return Line(Glyph.BLOCKED, "✗ Blocked · $detail")
    }

    private fun silenced(e164: String?, reason: Reason?, rules: Rules): Line {
        val detail = when (reason) {
            Reason.UNKNOWN_OUTSIDE_WINDOW ->
                "Unknown, outside ${bounds(rules.unknownWindow)}"
            Reason.BUSINESS_OUTSIDE_WINDOW ->
                "Business, outside ${bounds(rules.businessWindow)}"
            Reason.PATTERN_SILENCED -> patternDetail(rules.silencePatterns, e164)
            Reason.HIDDEN_POLICY -> "hidden-caller policy"
            Reason.SEND_TO_VOICEMAIL -> "send to voicemail"
            Reason.STIR_FAILED -> "STIR failed"
            else -> reason?.uiLabel ?: "policy"
        }
        return Line(Glyph.SILENCED, "→ Silenced · $detail")
    }

    private fun rang(reason: Reason?, weekday: String?): Line {
        var text = "✓ rang"
        when (reason) {
            Reason.RECENT_OUTGOING -> text += " · ${weekday?.let { "you called them $it" } ?: Reason.RECENT_OUTGOING.uiLabel}"
            Reason.STARRED -> text += " · starred"
            Reason.SAVED -> text += " · saved"
            Reason.BUSINESS_IN_WINDOW -> text += " · business in window"
            Reason.EMERGENCY_CALLBACK -> text += " · emergency callback"
            Reason.REPEAT_CALLER -> text += " · repeat caller"
            Reason.EXPECTING_A_CALL -> text += " · expecting a call"
            else -> Unit // UNKNOWN_IN_WINDOW and friends read as a plain ring
        }
        return Line(Glyph.RING, text)
    }

    /** Live window bounds as printed: inclusive start–exclusive end, "09–17". */
    fun bounds(w: com.piercingxx.xxdialer.core.Window): String =
        "${minuteText(w.startMinuteOfDay)}–${minuteText(w.endMinuteOfDay)}"

    private fun minuteText(minuteOfDay: Int): String =
        if (minuteOfDay % 60 == 0) "%02d".format(minuteOfDay / 60)
        else "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

    /**
     * The mask of the rule that actually matches this number — the log stores
     * only the enum name, so the fired rule is recovered from live patterns;
     * a since-deleted rule degrades to the bare word "pattern".
     */
    internal fun patternDetail(patterns: List<PatternRule>, e164: String?): String {
        val matched = e164?.let { n -> patterns.firstOrNull { it.matches(n) } }
        return matched?.let { "pattern ${mask(it)}" } ?: "pattern"
    }

    /**
     * `prefix 1425555 + 4 wildcards` prints as `425-555-XXXX`: NANP numbers
     * drop their leading country code; the remainder groups 3-3-tail so a
     * wildcard tail never splits.
     */
    fun mask(rule: PatternRule): String {
        val digits = rule.prefix + "X".repeat(rule.wildcardCount)
        val trimmed = if (digits.length == 11 && digits.startsWith("1")) digits.substring(1) else digits
        return when {
            trimmed.length <= 3 -> trimmed
            trimmed.length <= 6 -> "${trimmed.substring(0, 3)}-${trimmed.substring(3)}"
            else ->
                "${trimmed.substring(0, 3)}-${trimmed.substring(3, 6)}-${trimmed.substring(6)}"
        }
    }

    private fun toReason(raw: String): Reason? =
        raw.trim().takeIf { it.isNotEmpty() }?.let { r ->
            runCatching { Reason.valueOf(r.uppercase()) }.getOrNull()
        }
}
