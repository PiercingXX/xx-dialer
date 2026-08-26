package com.piercingxx.xxdialer.telecom

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnhancementWindowsTest {

    private val now = 1_800_000_000_000L

    @Test
    fun repeat_1459_isInside_1500_isOutside() {
        val fourteenFiftyNine = 14L * 60 * 1000 + 59L * 1000
        val fifteen = 15L * 60 * 1000
        assertTrue(EnhancementWindows.inRepeatWindow(now, now - fourteenFiftyNine))
        assertTrue(EnhancementWindows.inRepeatWindow(now, now - fifteen + 1))
        assertFalse(EnhancementWindows.inRepeatWindow(now, now - fifteen))
        assertFalse(EnhancementWindows.inRepeatWindow(now, now - fifteen - 1_000))
    }

    @Test
    fun recentOutgoing_4759_isInside_4800_isOutside() {
        val fortySevenFiftyNine = 47L * 60 * 60 * 1000 + 59L * 60 * 1000
        val fortyEight = 48L * 60 * 60 * 1000
        assertTrue(EnhancementWindows.inRecentOutgoingWindow(now, now - fortySevenFiftyNine))
        assertTrue(EnhancementWindows.inRecentOutgoingWindow(now, now - fortyEight + 1))
        assertFalse(EnhancementWindows.inRecentOutgoingWindow(now, now - fortyEight))
        assertFalse(EnhancementWindows.inRecentOutgoingWindow(now, now - fortyEight - 1_000))
    }
}
