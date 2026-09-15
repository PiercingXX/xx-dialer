package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisconnectNoticeTest {

    @Test
    fun busy_code_or_supervisory_tone_keeps_the_card() {
        assertTrue(DisconnectNotice.shouldHold(DisconnectNotice.CODE_BUSY, 0))
        assertTrue(DisconnectNotice.shouldHold(0, DisconnectNotice.TONE_BUSY))
        assertTrue(DisconnectNotice.shouldHold(1, DisconnectNotice.TONE_CONGESTION))
        assertFalse(DisconnectNotice.shouldHold(2, 27))
        assertFalse(DisconnectNotice.shouldHold(3, 27))
    }

    @Test
    fun tone_prefers_what_telecom_asked_for() {
        assertEquals(
            DisconnectNotice.TONE_CONGESTION,
            DisconnectNotice.toneToPlay(DisconnectNotice.CODE_BUSY, DisconnectNotice.TONE_CONGESTION),
        )
        assertEquals(
            DisconnectNotice.TONE_BUSY,
            DisconnectNotice.toneToPlay(DisconnectNotice.CODE_BUSY, 0),
        )
    }

    @Test
    fun detail_falls_back_to_line_busy() {
        assertEquals("Line busy", DisconnectNotice.detail(null))
        assertEquals("Line busy", DisconnectNotice.detail("  "))
        assertEquals("User busy", DisconnectNotice.detail("User busy"))
    }
}
