package com.piercingxx.xxdialer.ring

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissedNotifPolicyTest {

    @Test
    fun immediate_alwaysPosts() {
        assertTrue(MissedNotifPolicy.shouldPost("immediate", "Silence", "enforced"))
        assertTrue(MissedNotifPolicy.shouldPost("immediate", "Ring", "enforced"))
        assertTrue(MissedNotifPolicy.shouldPost("immediate", null, null))
    }

    @Test
    fun never_skipsEnforcedSilence_postsRingAndObservedSilence() {
        assertFalse(MissedNotifPolicy.shouldPost("never", "Silence", "enforced"))
        assertTrue(MissedNotifPolicy.shouldPost("never", "Ring", "enforced"))
        assertTrue(MissedNotifPolicy.shouldPost("never", "Silence", "observed"))
        assertTrue(MissedNotifPolicy.shouldPost("never", null, null))
        assertTrue(MissedNotifPolicy.shouldPost("never", "Block", "enforced"))
    }
}
