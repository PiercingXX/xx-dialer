package com.piercingxx.xxdialer.telecom

import com.piercingxx.xxdialer.core.CallerFacts
import com.piercingxx.xxdialer.core.HiddenCallerPolicy
import com.piercingxx.xxdialer.core.Mode
import com.piercingxx.xxdialer.core.PatternRule
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.RingPolicy
import com.piercingxx.xxdialer.core.Rules
import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Tone
import com.piercingxx.xxdialer.core.Verdict
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The log-row labels must agree with what RingPolicy actually decided (§11,
 * R7): every label here is asserted against the real decide() output.
 */
class LogRowsTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 8, 12, 10, 0) // Wednesday, inside 9–17
    private val e164 = "+14155550100"

    private fun facts(
        number: String? = e164,
        saved: Boolean = false,
        starred: Boolean = false,
        bizTier: Boolean = false,
        sendToVoicemail: Boolean = false,
        stirFailed: Boolean = false,
        repeatCaller: Boolean = false,
        recentOutgoing: Boolean = false,
        emergencyWindow: Boolean = false,
    ) = CallerFacts(
        number = number, saved = saved, starred = starred, bizTier = bizTier,
        sendToVoicemail = sendToVoicemail, userBlocked = false, stirFailed = stirFailed,
        repeatCaller = repeatCaller, recentOutgoing = recentOutgoing,
        cnapName = null, emergencyWindow = emergencyWindow,
    )

    private fun label(verdict: Verdict, f: CallerFacts, rules: Rules): Reason? =
        LogRows.reason(verdict, f, rules, now)

    @Test
    fun blockByPattern_labeledPattern() {
        val rules = Rules(blockPatterns = listOf(PatternRule("1415555", 4)))
        val v = RingPolicy.decide(now, facts(), rules)
        assertEquals(Reason.PATTERN_BLOCKED, label(v, facts(), rules))
    }

    @Test
    fun blockByStir_labeledStir() {
        val v = RingPolicy.decide(now, facts(stirFailed = true), Rules())
        assertEquals(Reason.STIR_FAILED, label(v, facts(stirFailed = true), Rules()))
    }

    @Test
    fun ringPrecedence_emergencyBeatsStarredBeatsSaved() {
        val v = RingPolicy.decide(now, facts(saved = true, starred = true, emergencyWindow = true), Rules())
        assertEquals(Reason.EMERGENCY_CALLBACK, label(v, facts(emergencyWindow = true), Rules()))
    }

    @Test
    fun ringSaved_savedLabel() {
        val v = RingPolicy.decide(now, facts(saved = true), Rules())
        assertEquals(Reason.SAVED, label(v, facts(saved = true), Rules()))
    }

    @Test
    fun ringRecentOutgoing_recentLabel() {
        val v = RingPolicy.decide(now, facts(recentOutgoing = true), Rules())
        assertEquals(Reason.RECENT_OUTGOING, label(v, facts(recentOutgoing = true), Rules()))
    }

    @Test
    fun unknownInWindow_unknownLabel() {
        val v = RingPolicy.decide(now, facts(), Rules())
        assertEquals(Verdict.Ring(Tone.UNKNOWN), v)
        assertEquals(Reason.UNKNOWN_IN_WINDOW, label(v, facts(), Rules()))
    }

    @Test
    fun silenceOutsideWindow_passesThrough() {
        val later = now.withHour(21) // outside 9–17
        val v = RingPolicy.decide(later, facts(), Rules())
        assertEquals(Reason.UNKNOWN_OUTSIDE_WINDOW, LogRows.reason(v, facts(), Rules(), later))
    }

    @Test
    fun repeatPierce_outsideWindow_labelsRepeatCaller() {
        val later = now.withHour(21)
        val f = facts(repeatCaller = true)
        val v = RingPolicy.decide(later, f, Rules())
        assertEquals(Verdict.Ring(Tone.UNKNOWN), v) // D10 pierced
        assertEquals(Reason.REPEAT_CALLER, LogRows.reason(v, f, Rules(), later))
    }

    @Test
    fun expectingACall_bypassArmed_labelsExpecting() {
        val later = now.withHour(21)
        val rules = Rules(bypassUntil = later.plusMinutes(30))
        val v = RingPolicy.decide(later, facts(), rules)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), v) // D15 pierced
        assertEquals(Reason.EXPECTING_A_CALL, LogRows.reason(v, facts(), rules, later))
    }

    @Test
    fun hiddenSilencePolicy_silencedLabel() {
        val rules = Rules(hiddenCallerPolicy = HiddenCallerPolicy.SILENCE)
        val f = facts(number = null).copy(withheld = true)
        val v = RingPolicy.decide(now, f, rules)
        assertEquals(Reason.HIDDEN_POLICY, label(v, f, rules))
    }

    // --- L2: the silence arms baseWasSilence used to omit ----------------------

    @Test
    fun stirSilence_piercedByRepeat_labeledRepeatCaller() {
        val f = facts(stirFailed = true, repeatCaller = true)
        val rules = Rules(stirAction = StirAction.SILENCE)
        val v = RingPolicy.decide(now, f, rules)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), v) // D10 pierced the STIR-SILENCE
        assertEquals(Reason.REPEAT_CALLER, label(v, f, rules), "L2: pierced stir-silence was mislabeled")
    }

    @Test
    fun sendToVoicemail_isNotPiercedByRepeat() {
        val f = facts(sendToVoicemail = true, repeatCaller = true)
        val v = RingPolicy.decide(now, f, Rules())
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), v)
        assertEquals(Reason.SEND_TO_VOICEMAIL, label(v, f, Rules()))
    }

    @Test
    fun starredStirSilence_piercedByRepeat_labeledRepeatCaller_notStarred() {
        val f = facts(starred = true, stirFailed = true, repeatCaller = true)
        val rules = Rules(stirAction = StirAction.SILENCE)
        val v = RingPolicy.decide(now, f, rules)
        assertEquals(Verdict.Ring(Tone.UNKNOWN), v)
        assertEquals(Reason.REPEAT_CALLER, label(v, f, rules), "pierce beats the tier label (R7)")
    }

    @Test
    fun sendToVoicemail_isNotPiercedByExpecting() {
        val later = now.withHour(21)
        val f = facts(sendToVoicemail = true)
        val rules = Rules(bypassUntil = later.plusMinutes(30))
        val v = RingPolicy.decide(later, f, rules)
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), v)
        assertEquals(Reason.SEND_TO_VOICEMAIL, LogRows.reason(v, f, rules, later))
    }

    @Test
    fun sendToVoicemail_unpierced_staysSilencedByVoicemail() {
        val f = facts(sendToVoicemail = true)
        val v = RingPolicy.decide(now, f, Rules())
        assertEquals(Verdict.Silence(Reason.SEND_TO_VOICEMAIL), v)
        assertEquals(Reason.SEND_TO_VOICEMAIL, label(v, f, Rules()))
    }

    @Test
    fun tierAndModeNames() {
        assertEquals("biz", LogRows.tier(facts(bizTier = true)))
        assertNull(LogRows.tier(facts()))
        // Wire format, not copy: RecentsMerge and XxInCallService match on
        // these and BackupJson carries them between devices. Rewording the UI
        // must never move them.
        assertEquals("enforced", LogRows.modeName(Mode.ENFORCING))
        assertEquals("observed", LogRows.modeName(Mode.OBSERVING))
    }

    @Test
    fun modeLabelSpeaksTheUsersQuestionNotTheStateMachines() {
        assertEquals("silencing", LogRows.modeLabel(LogRows.modeName(Mode.ENFORCING)))
        assertEquals("watching", LogRows.modeLabel(LogRows.modeName(Mode.OBSERVING)))
    }

    @Test
    fun modeLabelPrintsAnUnknownTokenVerbatimRatherThanGuessing() {
        // A row written by some other build must say what it says; silently
        // relabelling it would put a claim in the log that nothing made.
        assertEquals("supervised", LogRows.modeLabel("supervised"))
        assertEquals("", LogRows.modeLabel(""))
    }

    @Test
    fun dispositionWordFollowsThePrintedVerdictVocabulary() {
        assertEquals("Silenced", LogRows.dispositionWord("Silence"))
        assertEquals("Blocked", LogRows.dispositionWord("Block"))
        assertEquals("Rang", LogRows.dispositionWord("Ring"))
        // Prefix-matched, so qualified class names from older rows still read.
        assertEquals("Silenced", LogRows.dispositionWord("silence"))
    }

    @Test
    fun dispositionWordInventsNothingForATokenItDoesNotKnow() {
        assertNull(LogRows.dispositionWord(null))
        assertNull(LogRows.dispositionWord(""))
        assertNull(LogRows.dispositionWord("Escalate"))
    }
}
