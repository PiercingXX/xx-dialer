package com.piercingxx.xxphone.ui

import com.piercingxx.xxphone.core.PatternRule
import com.piercingxx.xxphone.core.Rules
import com.piercingxx.xxphone.core.Window
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The R7 verdict line (§12.1): `✓ rang` / `→ Silenced · Unknown, outside
 * 09–17` / `✗ Blocked · pattern 425-555-XXXX`, with live window bounds and
 * pattern masks substituted from the rules, never from stale label text.
 */
class VerdictLinesTest {

    private val e164 = "+14255550100"
    private val rules = Rules() // default windows 09–17 / 09–19 (§7)

    // ---- canonical forms ----------------------------------------------------

    @Test
    fun a_plain_ring_prints_the_check() {
        val line = VerdictLines.annotate(e164, "Ring", "UNKNOWN_IN_WINDOW", false, rules)
        assertEquals("✓ rang", line.text)
        assertEquals(VerdictLines.Glyph.RING, line.glyph)
    }

    @Test
    fun an_out_of_window_silence_prints_the_arrow_and_live_bounds() {
        val line = VerdictLines.annotate(e164, "Silence", "UNKNOWN_OUTSIDE_WINDOW", false, rules)
        assertEquals("→ Silenced · Unknown, outside 09–17", line.text)
        assertEquals(VerdictLines.Glyph.SILENCED, line.glyph)
    }

    @Test
    fun window_bounds_are_substituted_from_the_live_rules_not_the_label() {
        // The user moved the unknown window; the printed bounds must follow.
        val edited = rules.copy(
            unknownWindow = Window(10 * 60, 18 * 60 + 30, Window.ALL_DAYS),
        )
        val line = VerdictLines.annotate(e164, "Silence", "UNKNOWN_OUTSIDE_WINDOW", false, edited)
        assertEquals("→ Silenced · Unknown, outside 10–18:30", line.text)
    }

    @Test
    fun a_pattern_block_prints_the_mask_of_the_fired_rule() {
        val line = VerdictLines.annotate(
            e164, "Block", "PATTERN_BLOCKED", false,
            rules.copy(blockPatterns = listOf(PatternRule("1425555", 4))),
        )
        assertEquals("✗ Blocked · pattern 425-555-XXXX", line.text)
        assertEquals(VerdictLines.Glyph.BLOCKED, line.glyph)
    }

    @Test
    fun a_pattern_silence_prints_its_own_mask_from_silence_rules() {
        val line = VerdictLines.annotate(
            e164, "Silence", "PATTERN_SILENCED", false,
            rules.copy(silencePatterns = listOf(PatternRule("1425555", 4))),
        )
        assertEquals("→ Silenced · pattern 425-555-XXXX", line.text)
    }

    @Test
    fun a_deleted_pattern_degrades_to_the_bare_word() {
        val line = VerdictLines.annotate(e164, "Block", "PATTERN_BLOCKED", false, Rules())
        assertEquals("✗ Blocked · pattern", line.text)
    }

    // ---- business window ------------------------------------------------------

    @Test
    fun business_outside_window_prints_business_bounds() {
        val line = VerdictLines.annotate(e164, "Silence", "BUSINESS_OUTSIDE_WINDOW", false, rules)
        assertEquals("→ Silenced · Business, outside 09–19", line.text)

        val edited = rules.copy(businessWindow = Window(8 * 60, 20 * 60, Window.ALL_DAYS))
        val updated = VerdictLines.annotate(e164, "Silence", "BUSINESS_OUTSIDE_WINDOW", false, edited)
        assertEquals("→ Silenced · Business, outside 08–20", updated.text)
    }

    // ---- ring suffixes ----------------------------------------------------------

    @Test
    fun recent_outgoing_substitutes_the_weekday_when_known() {
        val line = VerdictLines.annotate(
            e164, "Ring", "RECENT_OUTGOING", false, rules, recentOutgoingWeekday = "Thu",
        )
        assertEquals("✓ rang · you called them Thu", line.text)
    }

    @Test
    fun recent_outgoing_keeps_the_static_skeleton_without_a_weekday() {
        val line = VerdictLines.annotate(e164, "Ring", "RECENT_OUTGOING", false, rules)
        assertEquals("✓ rang · you called them Tue", line.text)
    }

    @Test
    fun observe_mode_is_part_of_the_record() {
        val line = VerdictLines.annotate(e164, "Silence", "HIDDEN_POLICY", true, rules)
        assertEquals("→ Silenced · hidden-caller policy · observed", line.text)
    }

    @Test
    fun other_ring_reasons_read_as_suffixes() {
        assertEquals(
            "✓ rang · starred",
            VerdictLines.annotate(e164, "Ring", "STARRED", false, rules).text,
        )
        assertEquals(
            "✓ rang · repeat caller",
            VerdictLines.annotate(e164, "Ring", "REPEAT_CALLER", false, rules).text,
        )
        assertEquals(
            "✓ rang · expecting a call",
            VerdictLines.annotate(e164, "Ring", "EXPECTING_A_CALL", false, rules).text,
        )
    }

    // ---- platform-only rows -----------------------------------------------------

    @Test
    fun upstream_blocks_annotate_from_calllog_block_types() {
        val line = VerdictLines.annotate(null, null, "", false, rules, blockedUpstream = true)
        assertEquals("✗ Blocked · upstream setting", line.text)
        assertEquals(VerdictLines.Glyph.BLOCKED, line.glyph)
    }

    @Test
    fun unscreened_rows_render_nothing() {
        val line = VerdictLines.annotate(e164, null, "", false, rules)
        assertEquals("", line.text)
        assertEquals(VerdictLines.Glyph.NONE, line.glyph)
    }

    @Test
    fun blocklist_blocks_name_the_mechanism() {
        val line = VerdictLines.annotate(e164, "Block", "USER_BLOCKED", false, rules)
        assertEquals("✗ Blocked · blocklist", line.text)
    }

    @Test
    fun stir_failures_keep_their_name() {
        assertEquals(
            "✗ Blocked · STIR failed",
            VerdictLines.annotate(e164, "Block", "STIR_FAILED", false, rules).text,
        )
        assertEquals(
            "→ Silenced · STIR failed",
            VerdictLines.annotate(e164, "Silence", "STIR_FAILED", false, rules).text,
        )
    }

    // ---- mask formatting ----------------------------------------------------------

    @Test
    fun nanp_masks_drop_the_country_code() {
        assertEquals("425-555-XXXX", VerdictLines.mask(PatternRule("1425555", 4)))
    }

    @Test
    fun non_nanp_masks_chunk_by_three() {
        assertEquals("446-555-0XX", VerdictLines.mask(PatternRule("4465550", 2)))
    }

    @Test
    fun zero_wildcards_print_a_full_prefix() {
        assertEquals("141-555", VerdictLines.mask(PatternRule("141555", 0)))
    }
}
