package com.piercingxx.xxdialer.telecom

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §15 clock-row contract: the 24 h emergency window closes only when BOTH
 * readings agree it is over; every single-reading corruption errs toward open.
 */
class EmergencyWindowTest {

    private val nowEpoch = 1_800_000_000_000L
    private val nowElapsed = 500_000L
    private val window = EmergencyWindow.WINDOW_MS

    @Test
    fun noMarker_isClosed() {
        assertFalse(EmergencyWindow.active(nowEpoch, null, nowElapsed, null))
    }

    @Test
    fun bothReadingsFresh_isOpen() {
        assertTrue(EmergencyWindow.active(nowEpoch, nowEpoch - window / 2, nowElapsed, nowElapsed - window / 2))
    }

    @Test
    fun bothReadingsStale_isClosed() {
        assertFalse(EmergencyWindow.active(nowEpoch, nowEpoch - window - 1, nowElapsed, nowElapsed - window - 1))
    }

    @Test
    fun exactly24hOld_isStale() {
        assertFalse(EmergencyWindow.active(nowEpoch, nowEpoch - window, nowElapsed, nowElapsed - window))
    }

    @Test
    fun clockMovedForward_wallLooksStale_elapsedKeepsItOpen() {
        assertTrue(EmergencyWindow.active(nowEpoch, nowEpoch - window - 3_600_000, nowElapsed, nowElapsed - 1_000))
    }

    @Test
    fun clockMovedBackward_wallClampsFresh_windowOpen() {
        // wall timestamp in the "future" of a moved-back clock → negative age.
        assertTrue(EmergencyWindow.active(nowEpoch, nowEpoch + 86_400_000, nowElapsed, nowElapsed - window - 1_000))
    }

    @Test
    fun shortUptimeElapsedStillFresh_keepsWindowOpenWhenWallStale() {
        assertTrue(
            EmergencyWindow.active(
                nowEpochMillis = nowEpoch,
                markerWallMillis = nowEpoch - window - 1,
                nowElapsedMillis = 10_000,
                markerElapsedMillis = 5_000,
            )
        )
        assertFalse(
            EmergencyWindow.active(
                nowEpochMillis = nowEpoch,
                markerWallMillis = nowEpoch - window - 1,
                nowElapsedMillis = 10_000,
                markerElapsedMillis = null,
            )
        )
    }

    @Test
    fun rebootMakesElapsedUnusable_staleWallClosesWindow() {
        assertFalse(
            EmergencyWindow.active(
                nowEpochMillis = nowEpoch,
                markerWallMillis = nowEpoch - window - 1,
                nowElapsedMillis = 10_000,
                markerElapsedMillis = 5_000_000_000L,
            )
        )
    }

    @Test
    fun rebootMakesElapsedUnusable_freshWallKeepsWindowOpen() {
        assertTrue(
            EmergencyWindow.active(
                nowEpochMillis = nowEpoch,
                markerWallMillis = nowEpoch - 1_000,
                nowElapsedMillis = 10_000,
                markerElapsedMillis = 5_000_000_000L,
            )
        )
    }
}
