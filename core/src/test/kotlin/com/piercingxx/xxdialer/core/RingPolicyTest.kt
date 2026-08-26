package com.piercingxx.xxdialer.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The §6 truth table, row by row, plus every conflict, boundary and override
 * §16 demands. One named test per contract line.
 */
class RingPolicyTest {

    private val noon = LocalDateTime.of(2026, 8, 20, 12, 0)      // Thursday — inside unknown window
    private val evening = LocalDateTime.of(2026, 8, 20, 20, 0)   // Thursday — outside both windows

    // Neighbor-spoof-style rule: 425-555-XXXX shape over our default caller.
    private val neighborSpoof = PatternRule(prefix = "1415555", wildcardCount = 4)

    private fun facts(
        number: String? = "+14155550100",
        saved: Boolean = false,
        starred: Boolean = false,
        bizTier: Boolean = false,
        sendToVoicemail: Boolean = false,
        userBlocked: Boolean = false,
        stirFailed: Boolean = false,
        repeatCaller: Boolean = false,
        recentOutgoing: Boolean = false,
        cnapName: String? = null,
        emergencyWindow: Boolean = false,
        withheld: Boolean = false,
    ) = CallerFacts(
        number, saved, starred, bizTier, sendToVoicemail, userBlocked,
        stirFailed, repeatCaller, recentOutgoing, cnapName, emergencyWindow, withheld,
    )

    private fun rules(
        bypassUntil: LocalDateTime? = null,
        hiddenCallerPolicy: HiddenCallerPolicy = HiddenCallerPolicy.UNKNOWN,
        stirAction: StirAction = StirAction.BLOCK,
        repeatCallerEnabled: Boolean = true,
        blockPatterns: List<PatternRule> = emptyList(),
        silencePatterns: List<PatternRule> = emptyList(),
    ) = Rules(
        hiddenCallerPolicy = hiddenCallerPolicy,
        stirAction = stirAction,
        repeatCallerEnabled = repeatCallerEnabled,
        bypassUntil = bypassUntil,
        blockPatterns = blockPatterns,
        silencePatterns = silencePatterns,
    )

    // ---------------------------------------------------------------- rows

    @Test
    fun verdict_tokens_are_stable_wire_names() {
        assertEquals("Block", Verdict.Block.token())
        assertEquals("Ring", Verdict.Ring(Tone.DEFAULT).token())
        assertEquals("Silence", Verdict.Silence(Reason.SEND_TO_VOICEMAIL).token())
    }

    @Test
    fun row1_emergencyWindow_beatsEveryLowerRow_includingUserBlocked() {
        // Emergency is row 1, blocklist is row 3 — first match wins, so the
        // emergency callback rings even for an otherwise-blocked number.
        val verdict = RingPolicy.decide(evening, facts(userBlocked = true, stirFailed = true, emergencyWindow = true), rules())
        assertEquals(Verdict.Ring(Tone.DEFAULT), verdict)
    }

    @Test
    fun row2_sendToVoicemail_silenced_withReasonLogged() {
        val verdict = RingPolicy.decide(noon, facts(sendToVoicemail = true), rules())
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), verdict)
    }

    @Test
    fun row2_voicemail_sitsAboveBlock_voicemailWins() {
        val verdict = RingPolicy.decide(noon, facts(sendToVoicemail = true, userBlocked = true), rules())
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), verdict)
    }

    @Test
    fun row2_voicemail_isNotPiercedByRepeat() {
        val verdict = RingPolicy.decide(
            evening,
            facts(sendToVoicemail = true, repeatCaller = true),
            rules(),
        )
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), verdict)
    }

    @Test
    fun row2_voicemail_isNotPiercedByExpecting() {
        val verdict = RingPolicy.decide(
            evening,
            facts(sendToVoicemail = true),
            rules(bypassUntil = evening.plusHours(2)),
        )
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), verdict)
    }

    @Test
    fun row3_userBlocked_blocks() {
        val verdict = RingPolicy.decide(noon, facts(userBlocked = true), rules())
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun row4_stirFailed_defaultBlockAction_blocks() {
        val verdict = RingPolicy.decide(noon, facts(stirFailed = true), rules(stirAction = StirAction.BLOCK))
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun row4_stirFailed_silenceAction_silencesWithStirReason() {
        val verdict = RingPolicy.decide(noon, facts(stirFailed = true), rules(stirAction = StirAction.SILENCE))
        assertEquals(Verdict.Silence(Reason.STIR_FAILED), verdict)
    }

    @Test
    fun row4_stirOff_fallsThrough_toUnknownWindowVerdict() {
        val verdict = RingPolicy.decide(noon, facts(stirFailed = true), rules(stirAction = StirAction.OFF))
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    @Test
    fun row5_starredContact_ringsDefault_anyTime() {
        // 03:00 — outside both windows; starred rings anyway.
        val night = LocalDateTime.of(2026, 8, 20, 3, 0)
        assertEquals(Verdict.Ring(Tone.DEFAULT), RingPolicy.decide(night, facts(starred = true), rules()))
        assertEquals(Verdict.Ring(Tone.DEFAULT), RingPolicy.decide(noon, facts(starred = true), rules()))
    }

    @Test
    fun row6_businessInsideWindow_ringsDefault() {
        val verdict = RingPolicy.decide(LocalDateTime.of(2026, 8, 20, 18, 59), facts(bizTier = true, saved = true), rules())
        assertEquals(Verdict.Ring(Tone.DEFAULT), verdict)
    }

    @Test
    fun row7_businessOutsideWindow_silencedWithBusinessReason() {
        val verdict = RingPolicy.decide(evening, facts(bizTier = true, saved = true), rules())
        assertEquals(Verdict.Silence(Reason.BUSINESS_OUTSIDE_WINDOW), verdict)
    }

    @Test
    fun row8_savedContact_ringsDefault_anyTime() {
        val night = LocalDateTime.of(2026, 8, 20, 3, 0)
        assertEquals(Verdict.Ring(Tone.DEFAULT), RingPolicy.decide(night, facts(saved = true), rules()))
    }

    @Test
    fun row9_recentOutgoing_ringsUnknown_anyTime() {
        // Outside the unknown window — recent-outgoing rings anyway.
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(evening, facts(recentOutgoing = true), rules()))
    }

    @Test
    fun row9_recentOutgoingFalse_fallsThroughToOutsideWindowSilence() {
        val verdict = RingPolicy.decide(evening, facts(), rules())
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), verdict)
    }

    @Test
    fun row10_silencePattern_beatsUnknownInsideWindow() {
        // Even inside 09–17, where an unpatterned unknown would ring.
        val verdict = RingPolicy.decide(noon, facts(), rules(silencePatterns = listOf(neighborSpoof)))
        assertEquals(Verdict.Silence(Reason.PATTERN_SILENCED), verdict)
    }

    @Test
    fun row11_unknownInsideWindow_ringsOnUnknownTone() {
        val verdict = RingPolicy.decide(noon, facts(), rules())
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    @Test
    fun row12_unknownOutsideWindow_silencedWithUnknownReason() {
        val verdict = RingPolicy.decide(evening, facts(), rules())
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), verdict)
    }

    // ------------------------------------------------------------ conflicts

    @Test
    fun conflict_starredAndUserBlocked_blockBeatsStarred() {
        val verdict = RingPolicy.decide(noon, facts(starred = true, userBlocked = true), rules())
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun conflict_starredBeatsBusiness_outsideBusinessWindow() {
        val verdict = RingPolicy.decide(evening, facts(starred = true, bizTier = true, saved = true), rules())
        assertEquals(Verdict.Ring(Tone.DEFAULT), verdict)
    }

    // No test for "business tier but not saved": the tier can only be assigned
    // from a contact row (§9), so bizTier && !saved cannot occur from real
    // facts — the invariant holds upstream by construction.

    // --------------------------------------------------- window boundaries

    @Test
    fun boundary_unknown_08h59m59s999999999_isOutside() {
        val t = LocalDateTime.of(2026, 8, 20, 8, 59, 59, 999_999_999)
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), RingPolicy.decide(t, facts(), rules()))
    }

    @Test
    fun boundary_unknown_09h00m00s000000000_isInside() {
        val t = LocalDateTime.of(2026, 8, 20, 9, 0, 0, 0)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(t, facts(), rules()))
    }

    @Test
    fun boundary_unknown_16h59m59s999999999_isInside() {
        val t = LocalDateTime.of(2026, 8, 20, 16, 59, 59, 999_999_999)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(t, facts(), rules()))
    }

    @Test
    fun boundary_unknown_17h00m00s000000000_exact_isOutside() {
        val t = LocalDateTime.of(2026, 8, 20, 17, 0, 0, 0)
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), RingPolicy.decide(t, facts(), rules()))
    }

    @Test
    fun boundary_business_18h59m59s999999999_isInside() {
        val t = LocalDateTime.of(2026, 8, 20, 18, 59, 59, 999_999_999)
        assertEquals(Verdict.Ring(Tone.DEFAULT), RingPolicy.decide(t, facts(bizTier = true, saved = true), rules()))
    }

    @Test
    fun boundary_business_19h00m00s000000000_exact_isOutside() {
        val t = LocalDateTime.of(2026, 8, 20, 19, 0, 0, 0)
        assertEquals(Verdict.Silence(Reason.BUSINESS_OUTSIDE_WINDOW), RingPolicy.decide(t, facts(bizTier = true, saved = true), rules()))
    }

    // -------------------------------------------------------- repeat caller

    @Test
    fun repeat_secondCallWithin15min_piercesUnknownOutsideSilence() {
        val verdict = RingPolicy.decide(evening, facts(repeatCaller = true), rules())
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    @Test
    fun repeat_disabledBySetting_staysSilenced() {
        val verdict = RingPolicy.decide(evening, facts(repeatCaller = true), rules(repeatCallerEnabled = false))
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), verdict)
    }

    @Test
    fun repeat_whileUserBlocked_blockStillWins() {
        val verdict = RingPolicy.decide(evening, facts(repeatCaller = true, userBlocked = true), rules())
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun repeat_piercesBusinessOutsideSilence() {
        val verdict = RingPolicy.decide(evening, facts(bizTier = true, saved = true, repeatCaller = true), rules())
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    @Test
    fun repeat_piercesPatternSilence() {
        val verdict = RingPolicy.decide(noon, facts(repeatCaller = true), rules(silencePatterns = listOf(neighborSpoof)))
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    @Test
    fun repeat_piercesHiddenPolicySilence() {
        val verdict = RingPolicy.decide(
            evening,
            facts(number = null, withheld = true, repeatCaller = true),
            rules(hiddenCallerPolicy = HiddenCallerPolicy.SILENCE),
        )
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    // ------------------------------------------------------ recent-outgoing

    @Test
    fun recentOutgoing_ringsOver_matchingSilencePattern_row9AboveRow10() {
        val verdict = RingPolicy.decide(noon, facts(recentOutgoing = true), rules(silencePatterns = listOf(neighborSpoof)))
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    // ----------------------------------------------------- expecting-a-call

    @Test
    fun bypass_active_nowEqualsUntil_boundaryIsInclusive_silenceRings() {
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(evening, facts(), rules(bypassUntil = evening)))
    }

    @Test
    fun bypass_active_convertsSilenceToUnknownRing() {
        val until = evening.plusMinutes(90)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(evening, facts(), rules(bypassUntil = until)))
    }

    @Test
    fun bypass_expired_staysSilenced() {
        val after = evening.plusNanos(1)
        val verdict = RingPolicy.decide(after, facts(), rules(bypassUntil = evening))
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), verdict)
    }

    @Test
    fun bypass_blockStillBlocks_duringActiveBypass() {
        val verdict = RingPolicy.decide(noon, facts(userBlocked = true), rules(bypassUntil = noon.plusHours(1)))
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun bypass_exactlyAtMaxDuration_eightHours_stillActive() {
        val until = evening.plusHours(8)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(evening, facts(), rules(bypassUntil = until)))
    }

    @Test
    fun bypass_beyondMaxDuration_clockBackwardsGuard_treatsAsExpired() {
        // §15: expiry implausibly far out means the clock jumped backwards —
        // cancel the bypass instead of honoring a stale value.
        val until = evening.plusHours(8).plusNanos(1)
        val verdict = RingPolicy.decide(evening, facts(), rules(bypassUntil = until))
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), verdict)
    }

    // ----------------------------------------------------------- withheld #

    @Test
    fun hidden_policyUnknown_flowsIntoRows11and12_asUnknown() {
        assertEquals(Verdict.Ring(Tone.UNKNOWN), RingPolicy.decide(noon, facts(number = null, withheld = true), rules()))
        assertEquals(
            Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW),
            RingPolicy.decide(evening, facts(number = null, withheld = true), rules()),
        )
    }

    @Test
    fun hidden_policySilence_withheldNumber_silencedWithHiddenReason() {
        val verdict = RingPolicy.decide(
            noon,
            facts(number = null, withheld = true),
            rules(hiddenCallerPolicy = HiddenCallerPolicy.SILENCE),
        )
        assertEquals(Verdict.Silence(Reason.HIDDEN_POLICY), verdict)
    }

    @Test
    fun hidden_policyBlock_withheldNumber_blocks() {
        val verdict = RingPolicy.decide(
            noon,
            facts(number = null, withheld = true),
            rules(hiddenCallerPolicy = HiddenCallerPolicy.BLOCK),
        )
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun unparseableAllowed_doesNotTakeHiddenBlock() {
        val verdict = RingPolicy.decide(
            noon,
            facts(number = null, withheld = false),
            rules(hiddenCallerPolicy = HiddenCallerPolicy.BLOCK),
        )
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    @Test
    fun unparseableAllowed_outsideWindow_isUnknownSilence() {
        val verdict = RingPolicy.decide(
            evening,
            facts(number = null, withheld = false),
            rules(hiddenCallerPolicy = HiddenCallerPolicy.BLOCK),
        )
        assertEquals(Verdict.Silence(Reason.UNKNOWN_OUTSIDE_WINDOW), verdict)
    }

    // ------------------------------------------------------- pattern rules

    @Test
    fun pattern_blockRule_unsavedNumber_blocks() {
        val verdict = RingPolicy.decide(noon, facts(), rules(blockPatterns = listOf(PatternRule("1415555", 4))))
        assertEquals(Verdict.Block, verdict)
    }

    @Test
    fun pattern_blockRule_savedNumber_contactsExemption_notBlocked() {
        // Belt-and-suspenders: callers already exempt contacts before feeding
        // facts; decide() refuses to pattern-block a saved number anyway.
        val verdict = RingPolicy.decide(noon, facts(saved = true), rules(blockPatterns = listOf(neighborSpoof)))
        assertEquals(Verdict.Ring(Tone.DEFAULT), verdict)
    }

    @Test
    fun pattern_silenceRule_savedNumber_contactsExemption_notSilenced() {
        val verdict = RingPolicy.decide(noon, facts(saved = true), rules(silencePatterns = listOf(neighborSpoof)))
        assertEquals(Verdict.Ring(Tone.DEFAULT), verdict)
    }

    @Test
    fun pattern_withheldNumber_cannotMatch_anyPattern() {
        val verdict = RingPolicy.decide(noon, facts(number = null, withheld = true), rules(silencePatterns = listOf(neighborSpoof), blockPatterns = listOf(neighborSpoof)))
        assertEquals(Verdict.Ring(Tone.UNKNOWN), verdict)
    }

    // --------------------------------------------------------------- CNAP

    @Test
    fun cnapName_neverChangesAnyVerdict() {
        data class Case(val now: LocalDateTime, val facts: CallerFacts, val rules: Rules)

        val cases = listOf(
            Case(noon, facts(), rules()),                                   // unknown rings
            Case(evening, facts(), rules()),                                // unknown silenced
            Case(noon, facts(userBlocked = true), rules()),                 // blocked
            Case(evening, facts(starred = true), rules()),                  // starred rings
            Case(evening, facts(bizTier = true, saved = true), rules()),    // business outside
            Case(noon, facts(sendToVoicemail = true), rules()),             // voicemail
            Case(noon, facts(emergencyWindow = true), rules()),             // emergency
            Case(evening, facts(number = null, withheld = true), rules(hiddenCallerPolicy = HiddenCallerPolicy.SILENCE)),
            Case(evening, facts(repeatCaller = true), rules()),             // pierced
        )
        for (case in cases) {
            val bare = RingPolicy.decide(case.now, case.facts.copy(cnapName = null), case.rules)
            val named = RingPolicy.decide(case.now, case.facts.copy(cnapName = "EVERGREEN DENTAL"), case.rules)
            assertEquals(bare, named, "CNAP changed the verdict for ${case.facts}")
        }
    }

    // ------------------------------------------------------- observe gate

    @Test
    fun observeMode_isInert_identicalVerdictsAcrossBothModes() {
        data class Case(val now: LocalDateTime, val facts: CallerFacts, val rules: Rules)

        val cases = listOf(
            Case(noon, facts(), rules()),
            Case(evening, facts(), rules()),
            Case(noon, facts(userBlocked = true), rules()),
            Case(noon, facts(starred = true), rules()),
            Case(evening, facts(bizTier = true, saved = true), rules()),
            Case(noon, facts(sendToVoicemail = true), rules()),
            Case(noon, facts(emergencyWindow = true), rules()),
            Case(evening, facts(number = null, withheld = true), rules(hiddenCallerPolicy = HiddenCallerPolicy.SILENCE)),
            Case(noon, facts(), rules(silencePatterns = listOf(neighborSpoof))),
            Case(evening, facts(repeatCaller = true), rules()),
            Case(noon, facts(), rules(bypassUntil = noon.plusHours(2))),
        )
        // Mode is inert by construction: decide() takes no Mode parameter and
        // the core keeps no state. Iterating both modes over the matrix must
        // produce byte-identical verdicts.
        val perMode = Mode.entries.map { mode ->
            require(mode in Mode.entries) // touch the value; the mapping below is mode-independent
            cases.map { RingPolicy.decide(it.now, it.facts, it.rules) }
        }
        perMode.drop(1).forEach { assertEquals(perMode[0], it) }
    }

    // ------------------------------------------------------------- defaults

    @Test
    fun rules_defaults_pinDesignValues_windows09to17and09to19() {
        val r = Rules()
        assertEquals(Window(540, 1020, Window.ALL_DAYS), r.unknownWindow)
        assertEquals(Window(540, 1140, Window.ALL_DAYS), r.businessWindow)
        assertEquals(HiddenCallerPolicy.UNKNOWN, r.hiddenCallerPolicy)
        assertEquals(StirAction.BLOCK, r.stirAction)
        assertTrue(r.repeatCallerEnabled)
        assertNull(r.bypassUntil)
        assertTrue(r.silencePatterns.isEmpty())
    }
}
