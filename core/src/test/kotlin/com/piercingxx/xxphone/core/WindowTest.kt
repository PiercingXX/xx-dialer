package com.piercingxx.xxphone.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §7 window semantics: inclusive start, exclusive end, midnight wrap, day mask. */
class WindowTest {

    private val sundayNoon = LocalDateTime.of(2026, 8, 23, 12, 0)  // bit 6
    private val mondayNoon = LocalDateTime.of(2026, 8, 24, 12, 0)  // bit 0

    @Test
    fun daysMask_bit0Monday_onlyMondayArrivalsInside() {
        val weekdaysFromMonday = Window(540, 1020, 1 shl 0)
        assertTrue(weekdaysFromMonday.contains(mondayNoon))
        assertFalse(weekdaysFromMonday.contains(sundayNoon))
    }

    @Test
    fun daysMask_bit6Sunday_onlySundayArrivalsInside() {
        val sundaysOnly = Window(540, 1020, 1 shl 6)
        assertTrue(sundaysOnly.contains(sundayNoon))
        assertFalse(sundaysOnly.contains(mondayNoon))
    }

    @Test
    fun wrappingWindow_eveningThroughEarlyMorning_inside_middayOutside() {
        // 22:00 -> 06:00, wraps past midnight (end <= start).
        val overnight = Window(22 * 60, 6 * 60, Window.ALL_DAYS)
        assertTrue(overnight.contains(LocalDateTime.of(2026, 8, 24, 23, 30)))
        assertTrue(overnight.contains(LocalDateTime.of(2026, 8, 25, 2, 0)))
        assertTrue(overnight.contains(LocalDateTime.of(2026, 8, 25, 5, 59, 59, 999_999_999)))
        assertFalse(overnight.contains(LocalDateTime.of(2026, 8, 25, 6, 0, 0, 0)))
        assertFalse(overnight.contains(mondayNoon))
    }

    @Test
    fun wrappingWindow_dayMaskCheckedAgainstArrivalDay() {
        // Sunday-only overnight span: late Sunday is in; 01:00 Monday arrives
        // on Monday, whose bit is clear — out, even though the span began Sunday.
        val sundayOvernight = Window(22 * 60, 6 * 60, 1 shl 6)
        assertTrue(sundayOvernight.contains(LocalDateTime.of(2026, 8, 23, 23, 30)))
        assertFalse(sundayOvernight.contains(LocalDateTime.of(2026, 8, 24, 1, 0)))
    }

    @Test
    fun dstSpringForward_wallClockSemantics_noGapInPureLocalTime() {
        // 2026-03-08, America/Los_Angeles: wall times 02:00–02:59 do not exist.
        // Windows evaluate the constructed LocalDateTime directly — no zone
        // conversion, so there is nothing to spring forward over.
        val earlyMorning = Window(1 * 60, 4 * 60, Window.ALL_DAYS)
        assertTrue(earlyMorning.contains(LocalDateTime.of(2026, 3, 8, 1, 59)))
        assertTrue(earlyMorning.contains(LocalDateTime.of(2026, 3, 8, 3, 0)))
        assertFalse(earlyMorning.contains(LocalDateTime.of(2026, 3, 8, 4, 0)))
    }

    @Test
    fun dstFallBack_wallClockSemantics_repeatedHourIsJustWallTime() {
        // 2026-11-01: 01:30 occurs twice in LA; as a LocalDateTime it is one
        // wall-clock instant and lands inside the window exactly once.
        val earlyMorning = Window(1 * 60, 4 * 60, Window.ALL_DAYS)
        assertTrue(earlyMorning.contains(LocalDateTime.of(2026, 11, 1, 1, 30)))
        assertFalse(earlyMorning.contains(LocalDateTime.of(2026, 11, 1, 0, 30)))
        assertFalse(earlyMorning.contains(LocalDateTime.of(2026, 11, 1, 4, 0)))
    }
}
