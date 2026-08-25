package com.piercingxx.xxdialer.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tile countdown formatting (§7.1, D15) — pure JVM. */
class TileCountdownTest {

    @Test
    fun `no deadline or stale deadline reads idle`() {
        assertEquals(TileCountdown.LABEL_IDLE, TileCountdown.label(null, nowMillis = 1_000L))
        assertEquals(TileCountdown.LABEL_IDLE, TileCountdown.label(999L, nowMillis = 1_000L), "expired ⇒ idle (D15: no indefinite)")
        assertFalse(TileCountdown.isActive(1_000L, 1_000L), "boundary is strict: at the deadline it is over")
    }

    @Test
    fun `active deadline renders the expecting countdown`() {
        val twoHours = 2 * 60 * 60 * 1000L
        assertEquals("Expecting · 2h00m", TileCountdown.label(twoHours, nowMillis = 0L))
        assertTrue(TileCountdown.isActive(twoHours - 1, nowMillis = 0L))
    }

    @Test
    fun `minutes-only remaining stays compact`() {
        assertEquals("Expecting · 45m", TileCountdown.label(45 * 60_000L, nowMillis = 0L))
        assertEquals("4m", TileCountdown.duration(4 * 60_000L))
    }

    @Test
    fun `remaining time rounds up so a live bypass never shows zero`() {
        assertEquals("1m", TileCountdown.duration(1L)) // 1 ms left is still one minute of bypass
        assertEquals("0m", TileCountdown.duration(0L))
        assertEquals("0m", TileCountdown.duration(-5L))
    }

    @Test
    fun `hours keep two-digit tabular minutes (section 12_1)`() {
        assertEquals("1h59m", TileCountdown.duration(119 * 60_000L))
        assertEquals("2h00m", TileCountdown.duration((119 * 60 + 59) * 1000L), "ceil: a second shy of 2h still reads 2h00m")
        assertEquals("1h05m", TileCountdown.duration(65 * 60_000L))
        assertEquals("10h00m", TileCountdown.duration(600 * 60_000L))
    }

    // --- L5: clock-skew guard, mirroring RingPolicy.bypassActive (>8 h ⇒ expired) ---

    @Test
    fun `deadline further out than max bypass reads expired`() {
        val eightHours = TileCountdown.MAX_BYPASS_MILLIS
        assertTrue(TileCountdown.isActive(eightHours, nowMillis = 0L), "exactly the max is still live")
        assertFalse(TileCountdown.isActive(eightHours + 1, nowMillis = 0L), ">8h ⇒ clock moved backwards ⇒ stale")
        assertEquals(TileCountdown.LABEL_IDLE, TileCountdown.label(eightHours + 1, nowMillis = 0L))
    }
}
