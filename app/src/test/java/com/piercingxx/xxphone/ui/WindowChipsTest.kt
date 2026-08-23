package com.piercingxx.xxphone.ui

import com.piercingxx.xxphone.core.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * daysMask ↔ chips mapping and window label building for the Rules screen
 * (§7, §12.4). Bit order is the core.Window contract: bit 0 = Monday ..
 * bit 6 = Sunday.
 */
class WindowChipsTest {

    // --- day bits -----------------------------------------------------------------

    @Test
    fun `day bits run Monday-first through Sunday`() {
        assertEquals(0b0000001, WindowChips.dayBit(0), "bit 0 = Monday")
        assertEquals(0b0001000, WindowChips.dayBit(3))
        assertEquals(0b1000000, WindowChips.dayBit(6), "bit 6 = Sunday")
        assertEquals(Window.ALL_DAYS, (0..6).fold(0) { acc, i -> acc or WindowChips.dayBit(i) })
    }

    @Test
    fun `toggle flips exactly one bit each way`() {
        val base = WindowChips.dayBit(0) or WindowChips.dayBit(4)
        val toggledOn = WindowChips.toggled(base, 2)
        assertTrue(toggledOn and WindowChips.dayBit(2) != 0)
        assertEquals(base, WindowChips.toggled(toggledOn, 2), "second toggle restores")
        assertFalse(WindowChips.chipsFromMask(base)[2])
        assertTrue(WindowChips.chipsFromMask(toggledOn)[2])
    }

    @Test
    fun `chips round-trip through mask`() {
        val mask = 0b0101101
        assertEquals(mask, WindowChips.maskFromChips(WindowChips.chipsFromMask(mask).toList()))
        assertEquals(0, WindowChips.maskFromChips(List(7) { false }))
        assertEquals(Window.ALL_DAYS, WindowChips.maskFromChips(List(7) { true }))
    }

    // --- labels -------------------------------------------------------------------

    @Test
    fun `clock labels are zero-padded 24h`() {
        assertEquals("09:00", WindowChips.clockLabel(540))
        assertEquals("17:00", WindowChips.clockLabel(1020))
        assertEquals("00:00", WindowChips.clockLabel(0))
        assertEquals("21:45", WindowChips.clockLabel(21 * 60 + 45))
    }

    @Test
    fun `time range joins start and end`() {
        assertEquals("09:00–17:00", WindowChips.timeRange(Window(540, 1020, 127)))
        assertEquals("22:00–06:00", WindowChips.timeRange(Window(22 * 60, 6 * 60, 127)))
    }

    @Test
    fun `days label compresses runs`() {
        assertEquals("every day", WindowChips.daysLabel(127))
        assertEquals("MON–FRI", WindowChips.daysLabel(0b0011111))
        assertEquals("SAT–SUN", WindowChips.daysLabel(0b1100000))
        assertEquals("MON, WED, FRI", WindowChips.daysLabel(0b0010101))
        assertEquals("TUE–THU, SAT", WindowChips.daysLabel(0b0101110))
        assertEquals("TUE–THU, SAT–SUN", WindowChips.daysLabel(0b1101110))
        assertEquals("no days", WindowChips.daysLabel(0))
    }

    @Test
    fun `window line combines both parts`() {
        assertEquals(
            "09:00–17:00 · every day",
            WindowChips.windowLine(Window(540, 1020, Window.ALL_DAYS)),
        )
        assertEquals(
            "09:00–19:00 · MON–FRI",
            WindowChips.windowLine(Window(540, 19 * 60, 0b0011111)),
        )
    }
}
