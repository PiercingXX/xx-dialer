package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the in-call surface is allowed to dismiss itself (§12 in-call).
 *
 * The two failures this latch exists to prevent are both invisible without a
 * SIM — an outgoing call that closes its own screen in the first frames, and
 * a screen that never leaves after the call ends — so the rule is a pure
 * one-shot latch and it is proven here instead.
 */
class CallEndExitTest {

    @Test
    fun empty_before_the_call_arrives_does_not_dismiss() {
        // placeCall() returns, the surface opens, Telecom has not added the
        // Call yet: several empty snapshots in a row, none of them an ending.
        val exit = CallEndExit()
        assertFalse(exit.fire(gridEmpty = true))
        assertFalse(exit.fire(gridEmpty = true))
        assertFalse(exit.fire(gridEmpty = true))
    }

    @Test
    fun empty_after_a_live_call_dismisses() {
        val exit = CallEndExit()
        assertFalse(exit.fire(gridEmpty = false))
        assertTrue(exit.fire(gridEmpty = true))
    }

    @Test
    fun the_optimistic_launch_then_a_real_call_then_hangup_dismisses_once() {
        val exit = CallEndExit()
        assertFalse(exit.fire(gridEmpty = true))  // launched ahead of the call
        assertFalse(exit.fire(gridEmpty = false)) // dialing
        assertFalse(exit.fire(gridEmpty = false)) // active
        assertTrue(exit.fire(gridEmpty = true))   // hung up: leave
    }

    @Test
    fun dismissal_fires_exactly_once() {
        // finish() is not something to call twice, and snapshots keep arriving
        // while the activity tears down.
        val exit = CallEndExit()
        exit.fire(gridEmpty = false)
        assertTrue(exit.fire(gridEmpty = true))
        assertFalse(exit.fire(gridEmpty = true))
        assertFalse(exit.fire(gridEmpty = true))
    }

    @Test
    fun a_call_arriving_after_dismissal_does_not_re_fire() {
        val exit = CallEndExit()
        exit.fire(gridEmpty = false)
        assertTrue(exit.fire(gridEmpty = true))
        assertFalse(exit.fire(gridEmpty = false))
        assertFalse(exit.fire(gridEmpty = true))
    }

    @Test
    fun a_live_grid_never_dismisses() {
        val exit = CallEndExit()
        repeat(5) { assertFalse(exit.fire(gridEmpty = false)) }
    }

    @Test
    fun call_waiting_never_empties_the_grid_so_never_dismisses_mid_call() {
        // Second call arrives, first is dropped: the grid is non-empty
        // throughout, so no snapshot in the sequence is an ending.
        val exit = CallEndExit()
        val gridEmptyOverTime = listOf(false, false, false, false)
        assertTrue(gridEmptyOverTime.none { exit.fire(it) })
    }
}
