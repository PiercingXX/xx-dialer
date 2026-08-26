package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.ui.RecentsMerge.Filter
import com.piercingxx.xxdialer.ui.RecentsMerge.LogRow
import com.piercingxx.xxdialer.ui.RecentsMerge.MergedCall
import com.piercingxx.xxdialer.ui.RecentsMerge.PlatformCall
import com.piercingxx.xxdialer.ui.VerdictLines.Glyph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CallLog × screen_log LEFT-JOIN, filter chips, and grouping (§12.1). */
class RecentsMergeTest {

    private val t0 = 1_760_000_000_000L

    private fun call(
        id: Long = 1,
        at: Long = t0,
        e164: String? = "+14155550100",
        missed: Boolean = false,
        blockedUpstream: Boolean = false,
    ) = PlatformCall(
        id = id, timeMillis = at, rawNumber = e164 ?: "hidden", e164 = e164,
        missed = missed, outgoing = false, blockedUpstream = blockedUpstream, durationSec = 0,
    )

    private fun log(
        id: Long = 100,
        at: Long = t0,
        e164: String? = "+14155550100",
        verdict: String = "Silence",
        reason: String = "UNKNOWN_OUTSIDE_WINDOW",
        mode: String = "enforced",
    ) = LogRow(id, at, e164, verdict, reason, mode)

    // ---- merge --------------------------------------------------------------

    @Test
    fun a_call_inside_the_proximity_window_carries_the_log_row() {
        val merged = RecentsMerge.merge(listOf(call(at = t0)), listOf(log(at = t0 + 5_000)))
        assertEquals(1, merged.size)
        assertEquals(Glyph.SILENCED, merged[0].glyph)
        assertEquals("UNKNOWN_OUTSIDE_WINDOW", merged[0].reasonRaw)
        assertEquals(100L, merged[0].screenLogId)
    }

    @Test
    fun a_call_outside_the_window_is_left_unannotated() {
        val merged = RecentsMerge.merge(listOf(call(at = t0)), listOf(log(at = t0 + 3 * 60_000)))
        assertEquals(Glyph.NONE, merged[0].glyph)
        assertEquals("", merged[0].reasonRaw)
    }

    @Test
    fun withheld_platform_row_pairs_with_withheld_log_on_time() {
        val merged = RecentsMerge.merge(
            listOf(call(id = 1, at = t0, e164 = null)),
            listOf(log(id = 50, at = t0 + 1_000, e164 = null, verdict = "Silence", reason = "HIDDEN_POLICY")),
        )
        assertEquals(Glyph.SILENCED, merged[0].glyph)
        assertEquals("HIDDEN_POLICY", merged[0].reasonRaw)
        assertEquals(50L, merged[0].screenLogId)
    }

    @Test
    fun withheld_does_not_steal_a_numbered_log() {
        val merged = RecentsMerge.merge(
            listOf(call(id = 1, at = t0, e164 = null), call(id = 2, at = t0, e164 = "+14155550100")),
            listOf(log(id = 50, at = t0, e164 = "+14155550100")),
        )
        val withheld = merged.first { it.key == 1L }
        val numbered = merged.first { it.key == 2L }
        assertEquals(Glyph.NONE, withheld.glyph)
        assertEquals(Glyph.SILENCED, numbered.glyph)
        assertEquals(50L, numbered.screenLogId)
    }

    @Test
    fun matching_requires_the_caller_to_normalize_first() {
        // Contract: PlatformCall.e164 is the normalized identity; a raw-only
        // row (e164 null) is treated as unmatchable, never fuzzy-matched.
        val rawOnly = call(e164 = null)
        assertNull(rawOnly.e164)
        val merged = RecentsMerge.merge(listOf(call()), listOf(log()))
        assertEquals(Glyph.SILENCED, merged[0].glyph)
    }

    @Test
    fun nearest_pairing_wins_when_two_logs_could_match() {
        val c = call(id = 1, at = 60_000L)
        val older = log(id = 10, at = 0L)          // 60 s away
        val newer = log(id = 11, at = 61_000L)     // 1 s away
        val merged = RecentsMerge.merge(listOf(c), listOf(older, newer))
        assertEquals(11L, merged[0].screenLogId)
    }

    @Test
    fun each_log_row_is_consumed_by_at_most_one_call() {
        val first = call(id = 1, at = 60_000L)
        val second = call(id = 2, at = 62_000L)
        val onlyLog = log(id = 10, at = 61_000L)
        val merged = RecentsMerge.merge(listOf(first, second), listOf(onlyLog))
        // exactly one of the two calls carries the annotation; the other stands bare
        assertEquals(1, merged.count { it.screenLogId == 10L })
        assertEquals(1, merged.count { it.screenLogId == null })
    }

    @Test
    fun upstream_blocked_rows_without_a_log_annotate_as_upstream() {
        val merged = RecentsMerge.merge(listOf(call(blockedUpstream = true)), emptyList())
        assertEquals(Glyph.BLOCKED, merged[0].glyph)
        assertTrue(merged[0].blockedUpstream)
    }

    @Test
    fun output_stays_newest_first() {
        val merged = RecentsMerge.merge(
            listOf(call(id = 1, at = t0), call(id = 2, at = t0 + 9_000)),
            emptyList(),
        )
        assertEquals(listOf(2L, 1L), merged.map { it.key })
    }

    @Test
    fun verdict_vocabulary_maps_to_glyphs() {
        assertEquals(Glyph.BLOCKED, RecentsMerge.glyphOf("Block"))
        assertEquals(Glyph.SILENCED, RecentsMerge.glyphOf("Silence"))
        assertEquals(Glyph.RING, RecentsMerge.glyphOf("Ring"))
        assertEquals(Glyph.NONE, RecentsMerge.glyphOf("Unknown"))
    }

    // ---- filters ------------------------------------------------------------

    @Test
    fun chip_filters_partition_the_list() {
        val missedSilenced = MergedCall(
            key = 1, e164 = null, rawNumber = null, timeMillis = t0,
            missed = true, outgoing = false, durationSec = 0,
            glyph = Glyph.SILENCED, reasonRaw = "", observed = false, screenLogId = null,
        )
        val placedRing = missedSilenced.copy(key = 2, missed = false, glyph = Glyph.RING)
        val blocked = missedSilenced.copy(key = 3, missed = false, glyph = Glyph.BLOCKED)

        assertTrue(RecentsMerge.passes(Filter.ALL, missedSilenced))
        assertTrue(RecentsMerge.passes(Filter.MISSED, missedSilenced))
        assertFalse(RecentsMerge.passes(Filter.MISSED, placedRing))
        assertTrue(RecentsMerge.passes(Filter.SILENCED, missedSilenced))
        assertFalse(RecentsMerge.passes(Filter.SILENCED, blocked))
        assertTrue(RecentsMerge.passes(Filter.BLOCKED, blocked))
        assertFalse(RecentsMerge.passes(Filter.BLOCKED, placedRing))
    }

    // ---- grouping -----------------------------------------------------------

    private fun numbered(n: Int, at: Long = t0 - n * 1_000L) =
        call(id = n.toLong(), at = at, e164 = "+14$n")

    private fun item(e164: String?, key: Long = 1) = MergedCall(
        key = key, e164 = e164, rawNumber = e164, timeMillis = t0,
        missed = false, outgoing = false, durationSec = 0,
        glyph = Glyph.RING, reasonRaw = "", observed = false, screenLogId = null,
    )

    @Test
    fun consecutive_same_number_calls_collapse_into_one_group() {
        val rows = RecentsMerge.group(
            listOf(item("+1555", 3), item("+1555", 2), item("+1444", 1)),
            groupingOn = true,
            expanded = emptySet(),
        )
        assertEquals(2, rows.size)
        val group = assertIs<RecentsMerge.Grouped.Group>(rows[0])
        assertEquals("+1555", group.identity)
        assertEquals(2, group.calls.size)
        assertEquals(false, group.expanded)
    }

    @Test
    fun non_consecutive_same_numbers_do_not_group() {
        val rows = RecentsMerge.group(
            listOf(item("+1555", 3), item("+1444", 2), item("+1555", 1)),
            groupingOn = true,
            expanded = emptySet(),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is RecentsMerge.Grouped.Single })
    }

    @Test
    fun expansion_state_comes_from_the_caller() {
        val rows = RecentsMerge.group(
            listOf(item("+1555", 2), item("+1555", 1)),
            groupingOn = true,
            expanded = setOf("+1555"),
        )
        val group = assertIs<RecentsMerge.Grouped.Group>(rows[0])
        assertTrue(group.expanded)
        assertEquals(2, group.calls.size) // children kept for flattening
    }

    @Test
    fun setting_off_leaves_every_call_single() {
        val rows = RecentsMerge.group(
            listOf(item("+1555", 3), item("+1555", 2)),
            groupingOn = false,
            expanded = setOf("+1555"),
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is RecentsMerge.Grouped.Single })
    }

    @Test
    fun withheld_numbers_stand_alone_even_when_adjacent() {
        val rows = RecentsMerge.group(
            listOf(item(null, 2), item(null, 1)),
            groupingOn = true,
            expanded = emptySet(),
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is RecentsMerge.Grouped.Single })
    }
}
