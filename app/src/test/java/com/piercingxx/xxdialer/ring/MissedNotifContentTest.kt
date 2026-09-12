package com.piercingxx.xxdialer.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissedNotifContentTest {

    @Test
    fun singleMissedKeepsTheAnnotatedTitle() {
        assertEquals("Missed call", MissedNotifContent.title(1))
        assertFalse(MissedNotifContent.usesInboxStyle(1))
    }

    @Test
    fun burstUsesInboxStyleAndCountTitle() {
        assertEquals("3 missed calls", MissedNotifContent.title(3))
        assertTrue(MissedNotifContent.usesInboxStyle(3))
        assertEquals(
            listOf("+15551212", "+15550000"),
            MissedNotifContent.inboxLines(listOf("+15551212", "", "+15550000", "+15559999"), limit = 2),
        )
    }

    @Test
    fun telecomLifetimeTotalIsNotTheTitleCount() {
        assertEquals(2, MissedNotifContent.displayCount(telecomCount = 847, unseenMissed = 2))
        assertEquals(1, MissedNotifContent.displayCount(telecomCount = 847, unseenMissed = 0))
        assertEquals(5, MissedNotifContent.displayCount(telecomCount = 5, unseenMissed = 5))
    }
}
