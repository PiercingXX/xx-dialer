package com.piercingxx.xxdialer.core

import java.time.LocalDateTime

/**
 * The ring policy (design §6): first match wins, in exactly the printed
 * order, then the overrides. Pure and deterministic — the same (now, facts,
 * rules) always yields the same verdict, on every device, in both modes.
 */
object RingPolicy {

    fun decide(now: LocalDateTime, facts: CallerFacts, rules: Rules): Verdict =
        applyOverrides(baseVerdict(now, facts, rules), facts, now, rules)

    private fun baseVerdict(now: LocalDateTime, facts: CallerFacts, rules: Rules): Verdict = when {
        facts.emergencyWindow -> Verdict.Ring(Tone.DEFAULT) // row 1
        // Row 2 interpretation: SEND_TO_VOICEMAIL is routed by the platform
        // itself; policy records why the phone stayed quiet rather than
        // deciding anything about the call.
        facts.sendToVoicemail -> Verdict.Silence(Reason.SEND_TO_VOICEMAIL) // row 2
        facts.userBlocked -> Verdict.Block // row 3
        matchesBlockPattern(facts, rules) -> Verdict.Block // row 3, pattern arm
        facts.stirFailed && rules.stirAction == StirAction.BLOCK -> Verdict.Block // row 4
        facts.stirFailed && rules.stirAction == StirAction.SILENCE ->
            Verdict.Silence(Reason.STIR_FAILED) // row 4
        facts.starred -> Verdict.Ring(Tone.DEFAULT) // row 5
        facts.bizTier && rules.businessWindow.contains(now) -> Verdict.Ring(Tone.DEFAULT) // row 6
        facts.bizTier -> Verdict.Silence(Reason.BUSINESS_OUTSIDE_WINDOW) // row 7
        facts.saved -> Verdict.Ring(Tone.DEFAULT) // row 8
        facts.recentOutgoing -> Verdict.Ring(Tone.UNKNOWN) // row 9
        else -> unknownVerdict(now, facts, rules)
    }

    /**
     * Withheld numbers take the hidden-caller policy ahead of the unknown
     * rows. UNKNOWN flows into rows 10–12 like any other unknown caller;
     * BLOCK/SILENCE short-circuit. Starred/saved are impossible for withheld
     * calls (no number to look up) so their rows above have already had their
     * say by the time we get here.
     */
    private fun unknownVerdict(now: LocalDateTime, facts: CallerFacts, rules: Rules): Verdict {
        if (facts.number == null) {
            return when (rules.hiddenCallerPolicy) {
                HiddenCallerPolicy.BLOCK -> Verdict.Block
                HiddenCallerPolicy.SILENCE -> Verdict.Silence(Reason.HIDDEN_POLICY)
                HiddenCallerPolicy.UNKNOWN -> windowVerdict(now, null, rules)
            }
        }
        return if (rules.silencePatterns.any { it.matches(facts.number) })
            Verdict.Silence(Reason.PATTERN_SILENCED) // row 10
        else windowVerdict(now, facts.number, rules)
    }

    private fun windowVerdict(now: LocalDateTime, number: String?, rules: Rules): Verdict =
        if (rules.unknownWindow.contains(now)) Verdict.Ring(Tone.UNKNOWN) // row 11
        else Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW) // row 12

    // Belt-and-suspenders contacts exemption (§8): callers feeding facts apply
    // it too, but a saved number matching any pattern must never be pattern-
    // blocked here. The explicit userBlocked signal above is not exemptable.
    private fun matchesBlockPattern(facts: CallerFacts, rules: Rules): Boolean =
        facts.number != null && !facts.saved &&
            rules.blockPatterns.any { it.matches(facts.number) }

    private fun applyOverrides(
        verdict: Verdict,
        facts: CallerFacts,
        now: LocalDateTime,
        rules: Rules,
    ): Verdict {
        var result = verdict
        // D10: a repeat caller pierces any Silence, never a Block. When this
        // fires, the log derives its REPEAT_CALLER reason from the facts.
        if (result is Verdict.Silence && facts.repeatCaller && rules.repeatCallerEnabled) {
            result = Verdict.Ring(Tone.UNKNOWN)
        }
        // D15: while Expecting-a-call is active, any remaining Silence rings.
        // Blocks still block.
        if (result is Verdict.Silence && bypassActive(now, rules.bypassUntil)) {
            result = Verdict.Ring(Tone.UNKNOWN)
        }
        return result
    }

    private fun bypassActive(now: LocalDateTime, until: LocalDateTime?): Boolean {
        if (until == null) return false
        if (now.isAfter(until)) return false
        // Clock moved backwards while armed (§15): an expiry further out than
        // the maximum configurable duration is stale — cancel the bypass.
        if (until.isAfter(now.plusHours(MAX_BYPASS_HOURS))) return false
        return true
    }

    private const val MAX_BYPASS_HOURS = 8L
}
