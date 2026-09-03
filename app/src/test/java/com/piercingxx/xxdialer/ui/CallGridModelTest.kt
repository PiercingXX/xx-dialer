package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call-grid reduction (§12 in-call, R1 hold/swap) — pure JVM: which
 * call is primary, when a second call waits, when swap is legal.
 */
class CallGridModelTest {

    private fun cell(key: String, line: Line) = Cell(key, "caller $key", line)

    // ---- idle / single call -------------------------------------------------

    @Test
    fun empty_grid_is_idle() {
        val grid = reduceGrid(emptyList())
        assertNull(grid.primary)
        assertNull(grid.waiting)
        assertFalse(grid.canSwap)
        assertTrue(grid.isEmpty)
    }

    @Test
    fun ended_calls_are_invisible() {
        val grid = reduceGrid(listOf(cell("a", Line.ENDED)))
        assertTrue(grid.isEmpty)
    }

    @Test
    fun single_active_call_is_primary_without_swap_or_waiting() {
        val grid = reduceGrid(listOf(cell("a", Line.ACTIVE)))
        assertEquals("a", grid.primary?.key)
        assertFalse(grid.primaryHeld)
        assertNull(grid.waiting)
        assertFalse(grid.canSwap)
    }

    @Test
    fun outgoing_connecting_call_is_primary_while_dialing() {
        val grid = reduceGrid(listOf(cell("d", Line.OUTGOING)))
        assertEquals("d", grid.primary?.key)
        assertFalse(grid.primaryHeld)
    }

    // ---- precedence -----------------------------------------------------------

    @Test
    fun active_outranks_outgoing_and_held() {
        val grid = reduceGrid(
            listOf(cell("h", Line.HELD), cell("o", Line.OUTGOING), cell("a", Line.ACTIVE)),
        )
        assertEquals("a", grid.primary?.key)
    }

    @Test
    fun held_only_call_is_primary_and_marked_held() {
        val grid = reduceGrid(listOf(cell("h", Line.HELD)))
        assertEquals("h", grid.primary?.key)
        assertTrue(grid.primaryHeld)
    }

    // ---- call waiting (§12) -----------------------------------------------------

    @Test
    fun ringing_second_call_becomes_waiting_card() {
        val grid = reduceGrid(
            listOf(cell("a", Line.ACTIVE), cell("w", Line.WAITING)),
        )
        assertEquals("a", grid.primary?.key)
        assertEquals("w", grid.waiting?.key)
        assertFalse(grid.canSwap)
    }

    @Test
    fun waiting_alone_is_incoming_not_swap() {
        val grid = reduceGrid(listOf(cell("w", Line.WAITING)))
        assertNull(grid.primary)
        assertEquals("w", grid.waiting?.key)
        assertFalse(grid.isEmpty)
    }

    // ---- hold and swap ------------------------------------------------------------

    @Test
    fun active_plus_held_enables_swap() {
        val grid = reduceGrid(
            listOf(cell("a", Line.ACTIVE), cell("h", Line.HELD)),
        )
        assertEquals("a", grid.primary?.key)
        assertTrue(grid.canSwap)
        assertNull(grid.waiting)
    }

    @Test
    fun two_actives_never_swap() {
        val grid = reduceGrid(
            listOf(cell("a", Line.ACTIVE), cell("b", Line.ACTIVE)),
        )
        assertFalse(grid.canSwap)
    }

    @Test
    fun disconnected_call_leaves_the_pair_so_swap_ends() {
        val before = reduceGrid(
            listOf(cell("a", Line.ACTIVE), cell("h", Line.ENDED)),
        )
        assertFalse(before.canSwap)
        val after = reduceGrid(
            listOf(cell("a", Line.HELD), cell("h", Line.ENDED)),
        )
        assertTrue(after.primaryHeld)
    }
}

/** Tabular mm:ss/h:mm:ss formatting for the §12.1 duration line — pure JVM. */
class DurationFormatTest {

    @Test
    fun zero_is_00_00() {
        assertEquals("00:00", formatDuration(0))
    }

    @Test
    fun under_an_hour_pads_minutes() {
        assertEquals("04:12", formatDuration(4 * 60 + 12))
    }

    @Test
    fun fifty_nine_fifty_nine_is_the_last_short_form() {
        assertEquals("59:59", formatDuration(59 * 60 + 59))
    }

    @Test
    fun an_hour_switches_to_h_mm_ss() {
        assertEquals("1:00:00", formatDuration(3600))
    }

    @Test
    fun hours_render_unpadded() {
        assertEquals("12:05:09", formatDuration(12 * 3600 + 5 * 60 + 9))
    }

    @Test
    fun negative_elapsed_clamps_to_zero() {
        assertEquals("00:00", formatDuration(-30))
    }

    @Test
    fun swap_unholds_parked_only_when_the_held_leg_reports_holding() {
        val req = SwapRequest(holdingKey = "a", parkedKey = "b")
        assertEquals("b", onHoldingForSwap(req, "a"))
        assertNull(onHoldingForSwap(req, "b"))
        assertNull(onHoldingForSwap(null, "a"))
    }
}

/** Audio-route sheet: only extras open a picker; built-in names stay ours. */
class RouteSheetTest {

    @Test
    fun phone_and_speaker_alone_do_not_open_a_sheet() {
        assertFalse(shouldShowRouteSheet(listOf(RouteKind.SPEAKER, RouteKind.EARPIECE)))
        assertFalse(shouldShowRouteSheet(listOf(RouteKind.SPEAKER)))
        assertFalse(shouldShowRouteSheet(emptyList()))
    }

    @Test
    fun any_accessory_opens_the_sheet() {
        assertTrue(shouldShowRouteSheet(listOf(RouteKind.SPEAKER, RouteKind.BLUETOOTH)))
        assertTrue(shouldShowRouteSheet(listOf(RouteKind.WIRED)))
        assertTrue(shouldShowRouteSheet(listOf(RouteKind.STREAMING, RouteKind.EARPIECE)))
    }

    @Test
    fun built_in_labels_are_ours_not_the_pixel_endpoint_names() {
        assertEquals("Speaker", routeLabel(RouteKind.SPEAKER, "Speakerphone"))
        assertEquals("Phone", routeLabel(RouteKind.EARPIECE, "Earpiece"))
        assertEquals("Bluetooth", routeLabel(RouteKind.BLUETOOTH, "Bluetooth"))
        assertEquals("Pixel Buds", routeLabel(RouteKind.BLUETOOTH, "Pixel Buds"))
        assertEquals("Wired headset", routeLabel(RouteKind.WIRED, "Wired headset"))
    }

    @Test
    fun generic_system_names_are_recognized() {
        assertTrue(isGenericRouteName("Earpiece"))
        assertTrue(isGenericRouteName(" SPEAKERPHONE "))
        assertFalse(isGenericRouteName("Pixel Buds Pro"))
    }
}
