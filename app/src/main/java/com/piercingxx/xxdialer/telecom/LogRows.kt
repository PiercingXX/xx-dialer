package com.piercingxx.xxdialer.telecom

import com.piercingxx.xxdialer.core.CallerFacts
import com.piercingxx.xxdialer.core.HiddenCallerPolicy
import com.piercingxx.xxdialer.core.Mode
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.Rules
import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Verdict
import java.time.LocalDateTime

/**
 * Derives what the screening log row should say about a verdict (§11, R7).
 * RingPolicy does not return the matched row number, so the why-it-rang
 * string is reconstructed from the facts in §6 precedence order. Labels only
 * — policy correctness lives in core, never here.
 */
object LogRows {

    fun reason(verdict: Verdict, facts: CallerFacts, rules: Rules, now: LocalDateTime): Reason? =
        when (verdict) {
            is Verdict.Block -> blockReason(facts, rules)
            is Verdict.Silence -> verdict.reason
            is Verdict.Ring -> ringReason(facts, rules, now)
        }

    private fun blockReason(facts: CallerFacts, rules: Rules): Reason {
        val number = facts.number
        return when {
            facts.userBlocked -> Reason.USER_BLOCKED // row 3, explicit signal — never exemptable
            number != null && rules.blockPatterns.any { it.matches(number) } ->
                Reason.PATTERN_BLOCKED // row 3, pattern arm
            facts.stirFailed -> Reason.STIR_FAILED // row 4
            else -> Reason.USER_BLOCKED // unreachable from decide(); keep a loud default
        }
    }

    private fun ringReason(facts: CallerFacts, rules: Rules, now: LocalDateTime): Reason = when {
        facts.emergencyWindow -> Reason.EMERGENCY_CALLBACK // row 1 dominates everything
        // D10/D15 pierces are evaluated BEFORE the tier rings: a repeat caller
        // piercing a starred/saved/send-to-voicemail/stir-silenced call must
        // read REPEAT_CALLER, not the tier label — the log states why it rang (R7).
        facts.repeatCaller && rules.repeatCallerEnabled && baseWasSilence(facts, rules, now) ->
            Reason.REPEAT_CALLER // D10 pierce
        baseWasSilence(facts, rules, now) && bypassActive(now, rules.bypassUntil) ->
            Reason.EXPECTING_A_CALL // D15 pierce
        facts.starred -> Reason.STARRED // row 5
        facts.bizTier && rules.businessWindow.contains(now) -> Reason.BUSINESS_IN_WINDOW // row 6
        facts.saved -> Reason.SAVED // row 8
        facts.recentOutgoing -> Reason.RECENT_OUTGOING // row 9
        else -> Reason.UNKNOWN_IN_WINDOW // rows 11 plain rings
    }

    /**
     * Would decide() have produced Silence before the overrides? Mirrors rows
     * 2/4-silence/7/10/12 plus hidden-SILENCE; rows 1–9 rings are excluded by
     * the arms above already having matched. The STIR-SILENCE and
     * SEND_TO_VOICEMAIL arms were missing (L2), mislabeling repeat-pierce rows
     * that rang through those silences.
     */
    private fun baseWasSilence(facts: CallerFacts, rules: Rules, now: LocalDateTime): Boolean {
        if (facts.sendToVoicemail) return true // row 2
        if (facts.stirFailed && rules.stirAction == StirAction.SILENCE) return true // row 4, silence arm
        if (facts.bizTier && !rules.businessWindow.contains(now)) return true // row 7
        val number = facts.number
        if (number != null && rules.silencePatterns.any { it.matches(number) }) return true // row 10
        if (number == null && rules.hiddenCallerPolicy == HiddenCallerPolicy.SILENCE) return true
        if (!rules.unknownWindow.contains(now)) return true // row 12
        return false
    }

    /** Same guard as RingPolicy's: clock moved backwards cancels an over-long bypass. */
    private fun bypassActive(now: LocalDateTime, until: LocalDateTime?): Boolean {
        if (until == null || now.isAfter(until)) return false
        return !until.isAfter(now.plusHours(MAX_BYPASS_HOURS))
    }

    private const val MAX_BYPASS_HOURS = 8L

    /** screen_log.tier (§11) — the only shipped tier. */
    fun tier(facts: CallerFacts): String? = if (facts.bizTier) "biz" else null

    /** screen_log.mode vocabulary: 'enforced' | 'observed' (todo rule #6). */
    fun modeName(mode: Mode): String =
        if (mode == Mode.ENFORCING) "enforced" else "observed"

    /**
     * What a log row SAYS about the mode it was written in, as opposed to
     * what it stores. The stored tokens above are wire format — RecentsMerge
     * and XxInCallService match on them and BackupJson carries them across
     * devices — so they are translated at the last moment instead of being
     * renamed at the source, which would have rewritten every existing row.
     * "enforced/observed" is state-machine vocabulary and answers the wrong
     * question; a user reading the log wants to know whether the call was
     * acted on or merely watched. An unrecognized token prints verbatim: a
     * row written by some other build should say what it says, not be
     * relabelled into a lie.
     */
    fun modeLabel(storedMode: String): String = when (storedMode) {
        modeName(Mode.ENFORCING) -> "silencing"
        modeName(Mode.OBSERVING) -> "watching"
        else -> storedMode
    }

    /**
     * The word for a stored screen_log.verdict token, matching §6's printed
     * wording. Prefix-matched and nullable because the token is whatever some
     * past build wrote (`Silence`, `Verdict.Silence`, …): an unrecognized
     * value earns no word at all rather than a guess — §15's rule against
     * inventing reasons applies to dispositions too.
     */
    fun dispositionWord(verdictToken: String?): String? = when {
        verdictToken == null -> null
        verdictToken.startsWith("silence", ignoreCase = true) -> "Silenced"
        verdictToken.startsWith("block", ignoreCase = true) -> "Blocked"
        verdictToken.startsWith("ring", ignoreCase = true) -> "Rang"
        else -> null
    }
}
