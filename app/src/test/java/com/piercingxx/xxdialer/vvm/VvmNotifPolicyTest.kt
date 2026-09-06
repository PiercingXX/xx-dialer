package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VvmNotifPolicyTest {

    @Test
    fun notifiesOnlyWhileToggleOn() {
        assertTrue(VvmNotifPolicy.shouldNotify(toggleOn = true))
        assertFalse(VvmNotifPolicy.shouldNotify(toggleOn = false))
    }

    @Test
    fun channelIdentityIsVoicemailV1Secret() {
        assertEquals("voicemail_v1", VvmNotifPolicy.CHANNEL_ID)
        assertEquals(1000, VvmNotifPolicy.CHANNEL_IMPORTANCE)
        assertEquals(1200, VvmNotifPolicy.NOTIFICATION_ID_BASE)
    }
}
