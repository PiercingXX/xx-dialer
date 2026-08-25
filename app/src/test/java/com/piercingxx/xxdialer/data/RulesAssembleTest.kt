package com.piercingxx.xxdialer.data

import com.piercingxx.xxdialer.core.HiddenCallerPolicy
import com.piercingxx.xxdialer.core.PatternRule
import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Window
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stored settings + pattern rows → core.Rules assembly (design §6, §7, §15). */
class RulesAssembleTest {

    // --- empty / garbage settings ---------------------------------------------------

    @Test
    fun `empty settings yield the shipped design defaults`() {
        val rules = RulesProvider.assemble(emptyMap(), emptyList())
        assertEquals(HiddenCallerPolicy.UNKNOWN, rules.hiddenCallerPolicy)
        assertEquals(StirAction.BLOCK, rules.stirAction)
        assertTrue(rules.repeatCallerEnabled, "repeat caller default on (§6)")
        assertNull(rules.bypassUntil)
        assertEquals(Window(540, 1020, 127), rules.unknownWindow)
        assertEquals(Window(540, 1140, 127), rules.businessWindow)
        assertTrue(rules.blockPatterns.isEmpty() && rules.silencePatterns.isEmpty())
    }

    @Test
    fun `garbage values degrade to defaults instead of throwing`() {
        val rules = RulesProvider.assemble(
            mapOf(
                SettingsRepository.KEY_HIDDEN_CALLER_POLICY to "lobotomize",
                SettingsRepository.KEY_STIR_ACTION to "42",
                SettingsRepository.KEY_UNKNOWN_WINDOW to "{oops",
                SettingsRepository.KEY_BUSINESS_WINDOW to "",
                SettingsRepository.KEY_REPEAT_CALLER_ENABLED to "certainly",
                SettingsRepository.KEY_BYPASS_UNTIL to "soon",
            ),
            emptyList(),
        )
        assertEquals(HiddenCallerPolicy.UNKNOWN, rules.hiddenCallerPolicy)
        assertEquals(StirAction.BLOCK, rules.stirAction)
        assertTrue(rules.repeatCallerEnabled, "anything but \"0\" reads as on")
        assertEquals(Window(540, 1020, 127), rules.unknownWindow, "unparseable window → §7 default")
        assertEquals(Window(540, 1140, 127), rules.businessWindow)
        assertNull(rules.bypassUntil)
    }

    @Test
    fun `stored windows and enums are honored`() {
        val rules = RulesProvider.assemble(
            mapOf(
                SettingsRepository.KEY_UNKNOWN_WINDOW to """{"startMinute":600,"endMinute":900,"daysMask":31}""",
                SettingsRepository.KEY_HIDDEN_CALLER_POLICY to "block",
                SettingsRepository.KEY_STIR_ACTION to "off",
                SettingsRepository.KEY_REPEAT_CALLER_ENABLED to "0",
            ),
            emptyList(),
        )
        assertEquals(Window(600, 900, 31), rules.unknownWindow)
        assertEquals(HiddenCallerPolicy.BLOCK, rules.hiddenCallerPolicy)
        assertEquals(StirAction.OFF, rules.stirAction)
        assertFalse(rules.repeatCallerEnabled)
    }

    // --- bypass epoch → LocalDateTime (system-default zone) --------------------------

    @Test
    fun `bypass epoch maps to a local datetime in the system zone`() {
        val epoch = 1_700_000_000_000L
        val rules = RulesProvider.assemble(
            mapOf(SettingsRepository.KEY_BYPASS_UNTIL to epoch.toString()),
            emptyList(),
        )
        assertEquals(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault()),
            rules.bypassUntil,
        )
    }

    @Test
    fun `blank bypass stays inactive`() {
        val rules = RulesProvider.assemble(mapOf(SettingsRepository.KEY_BYPASS_UNTIL to ""), emptyList())
        assertNull(rules.bypassUntil)
    }

    // --- pattern rows -----------------------------------------------------------------

    @Test
    fun `pattern rows split by action into block and silence lists`() {
        val rules = RulesProvider.assemble(
            emptyMap(),
            listOf(
                PatternRuleEntity(1, "1425555", 4, "block", null, 1L),
                PatternRuleEntity(2, "1800555", 4, "silence", "neighbor_spoof", 2L),
            ),
        )
        assertEquals(listOf(PatternRule("1425555", 4)), rules.blockPatterns)
        assertEquals(listOf(PatternRule("1800555", 4)), rules.silencePatterns)
    }

    @Test
    fun `malformed and unknown-action rows are skipped never thrown`() {
        val rules = RulesProvider.assemble(
            emptyMap(),
            listOf(
                PatternRuleEntity(1, "", 4, "block", null, 1L),
                PatternRuleEntity(2, "+1-800-FLOWERS", -1, "silence", null, 2L),
                PatternRuleEntity(3, "1425", 0, "weird", null, 3L),
            ),
        )
        assertTrue(rules.blockPatterns.isEmpty())
        assertTrue(rules.silencePatterns.isEmpty())
    }

    @Test
    fun `malformed prefixes are skipped not rewritten into different rules`() {
        // L6: stripping non-digits would LAUNDER corrupt rows into rules the
        // user never wrote — "+1 425-555" would become prefix 1425555, a
        // valid block rule matching real numbers. Corrupt ⇒ skipped, full stop.
        val rules = RulesProvider.assemble(
            emptyMap(),
            listOf(
                PatternRuleEntity(1, "+1 425-555", 4, "block", null, 1L),
                PatternRuleEntity(2, "+1-800-FLOWERS", 4, "silence", null, 2L),
                PatternRuleEntity(3, "  ", 0, "block", null, 3L),
            ),
        )
        assertTrue(rules.blockPatterns.isEmpty())
        assertTrue(rules.silencePatterns.isEmpty())
    }
}
