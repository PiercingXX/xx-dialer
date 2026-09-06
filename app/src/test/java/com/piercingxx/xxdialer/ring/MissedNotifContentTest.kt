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
}
