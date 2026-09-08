package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §6 precedence list as the Rules screen prints it (§12.4): rows 1–12 in
 * evaluation order, static condition labels carrying LIVE window values.
 */
class PrecedenceRowsTest {

    private val unknown = Window(540, 1020, Window.ALL_DAYS)
    private val business = Window(540, 1140, 0b0011111)

    @Test
    fun `rows are numbered one through twelve in order`() {
        val rows = PrecedenceRows.build(unknown, business)
        assertEquals((1..12).toList(), rows.map { it.n })
    }

    @Test
    fun `business rows print the live business window`() {
        val rows = PrecedenceRows.build(unknown, business)
        assertEquals("${GroupGlyphs.BUSINESS} Business tier, inside 09:00–19:00", rows[5].condition)
        assertEquals("${GroupGlyphs.BUSINESS} Business tier, outside 09:00–19:00", rows[6].condition)
    }

    @Test
    fun `unknown rows print the live unknown window`() {
        val rows = PrecedenceRows.build(unknown, business)
        assertEquals("Unknown, inside 09:00–17:00", rows[10].condition)
        assertEquals("Unknown, outside 09:00–17:00", rows[11].condition)
    }

    @Test
    fun `edited windows flow into labels`() {
        val rows = PrecedenceRows.build(Window(600, 900, 127), Window(480, 720, 127))
        assertEquals("${GroupGlyphs.BUSINESS} Business tier, inside 08:00–12:00", rows[5].condition)
        assertEquals("Unknown, inside 10:00–15:00", rows[10].condition)
    }

    @Test
    fun `stir row follows the setting`() {
        val block = PrecedenceRows.build(unknown, business, StirAction.BLOCK)[3]
        assertEquals(PrecedenceRows.Kind.BLOCK, block.kind)
        assertEquals("Block", block.verdict)

        val silence = PrecedenceRows.build(unknown, business, StirAction.SILENCE)[3]
        assertEquals(PrecedenceRows.Kind.SILENCE, silence.kind)

        val off = PrecedenceRows.build(unknown, business, StirAction.OFF)[3]
        assertEquals("off", off.verdict)
    }

    @Test
    fun `verdict vocabulary matches the mockup`() {
        val rows = PrecedenceRows.build(unknown, business).map { it.verdict }
        assertEquals(
            listOf(
                "Ring · any time",      // 1 emergency
                "→ voicemail",          // 2 send-to-voicemail
                "Block",                // 3 blocklist/pattern
                "Block",                // 4 STIR failed (default action)
                "Ring · any time",      // 5 starred
                "Ring",                 // 6 business in window
                "Silence",              // 7 business outside
                "Ring · any time",      // 8 saved
                "Ring · any time",      // 9 recent outgoing
                "Silence",              // 10 silence pattern
                "Ring",                 // 11 unknown in window
                "Silence",              // 12 unknown outside
            ),
            rows,
        )
        assertEquals("unknown tone", PrecedenceRows.build(unknown, business)[10].tone)
        assertEquals("default", PrecedenceRows.build(unknown, business)[0].tone)
    }

    @Test
    fun `kinds separate ring silence block and informational rows`() {
        val kinds = PrecedenceRows.build(unknown, business).map { it.kind }
        assertEquals(PrecedenceRows.Kind.RING, kinds[4], "starred rings")
        assertEquals(PrecedenceRows.Kind.SILENCE, kinds[6], "business outside silences")
        assertEquals(PrecedenceRows.Kind.BLOCK, kinds[2])
        assertEquals(PrecedenceRows.Kind.INFO, kinds[1], "voicemail routing is platform-owned")
    }
}
