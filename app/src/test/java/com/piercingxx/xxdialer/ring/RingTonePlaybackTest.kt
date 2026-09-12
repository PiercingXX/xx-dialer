package com.piercingxx.xxdialer.ring

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RingTonePlaybackTest {

    @Test
    fun starred_ringsThroughSilentRequest() {
        assertTrue(RingTonePlayback.allowTone(silentRequested = true, starred = true, emergencyWindow = false))
    }

    @Test
    fun emergency_ringsThroughSilentRequest() {
        assertTrue(RingTonePlayback.allowTone(silentRequested = true, starred = false, emergencyWindow = true))
    }

    @Test
    fun unstarred_honorsSilentRequest() {
        assertFalse(RingTonePlayback.allowTone(silentRequested = true, starred = false, emergencyWindow = false))
    }

    @Test
    fun noSilentRequest_alwaysAllowsTone() {
        assertTrue(RingTonePlayback.allowTone(silentRequested = false, starred = false, emergencyWindow = false))
    }

    @Test
    fun selfPlay_skipsWhenDndIsFiltering() {
        assertFalse(
            RingTonePlayback.selfPlay(
                insistent = true,
                waitingOverActive = false,
                dndFiltering = true,
            ),
        )
        assertTrue(
            RingTonePlayback.selfPlay(
                insistent = true,
                waitingOverActive = false,
                dndFiltering = false,
            ),
        )
    }

    @Test
    fun selfPlay_skipsOnceOnlyAndCallWaiting() {
        assertFalse(
            RingTonePlayback.selfPlay(
                insistent = false,
                waitingOverActive = false,
                dndFiltering = false,
            ),
        )
        assertFalse(
            RingTonePlayback.selfPlay(
                insistent = true,
                waitingOverActive = true,
                dndFiltering = false,
            ),
        )
    }
}
