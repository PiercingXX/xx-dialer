package com.piercingxx.xxphone.ui

import com.piercingxx.xxphone.telecom.XxCallScreeningService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * design.md §16, call-waiting bullet — the JVM-executable half of
 * androidTest/…/CallWaitingHoldSwapTest: the telecom-state → [Line] edge
 * mapping plus the hold/waiting/swap reductions the device run asserts against
 * live Calls (mirrors ui/CallGridModelTest rows through the edge).
 *
 * The mapping mirrors CallGrid.lineOf verbatim (pure copy so this stays
 * runnable on JVM without touching the Looper-bound live object) — keep the
 * three files in sync: ui/CallGrid.kt, androidTest CallWaitingHoldSwapTest.kt.
 */
class CallWaitingHoldSwapModelTest {

    /** Pure stand-in for CallGrid.lineOf over raw Telecom STATE_* ints. */
    private fun lineOf(telecomState: Int): Line = when (telecomState) {
        android.telecom.Call.STATE_ACTIVE,
        android.telecom.Call.STATE_AUDIO_PROCESSING,
        -> Line.ACTIVE
        android.telecom.Call.STATE_HOLDING -> Line.HELD
        android.telecom.Call.STATE_RINGING -> Line.WAITING
        android.telecom.Call.STATE_DIALING,
        android.telecom.Call.STATE_CONNECTING,
        -> Line.OUTGOING
        else -> Line.ENDED
    }

    private fun cell(key: String, telecomState: Int) = Cell(
        key = key,
        label = "caller $key",
        line = lineOf(telecomState),
    )

    private fun grid(vararg calls: Pair<String, Int>) = reduceGrid(calls.map { (k, s) -> cell(k, s) })

    // ---- call waiting (§12): second call arrives mid-call -------------------------

    @Test
    fun ringing_second_call_is_the_waiting_card_not_a_swap_pair() {
        val grid = grid("a" to android.telecom.Call.STATE_ACTIVE, "w" to android.telecom.Call.STATE_RINGING)
        assertEquals("a", grid.primary?.key)
        assertEquals("w", grid.waiting?.key)
        assertFalse(grid.canSwap)
    }

    @Test
    fun outgoing_second_leg_while_first_holding_is_not_yet_swappable() {
        val grid = grid(
            "a" to android.telecom.Call.STATE_HOLDING,
            "b" to android.telecom.Call.STATE_DIALING,
        )
        assertEquals("b", grid.primary?.key)
        assertFalse(grid.primaryHeld)
        assertFalse(grid.canSwap)
    }

    @Test
    fun hold_and_answer_turns_waiting_into_active_and_opens_swap() {
        val accepted = grid(
            "a" to android.telecom.Call.STATE_HOLDING,
            "w" to android.telecom.Call.STATE_ACTIVE,
        )
        assertEquals("w", accepted.primary?.key)
        assertTrue(accepted.canSwap)
    }

    // ---- swap (R1) -----------------------------------------------------------------

    @Test
    fun active_plus_held_enables_swap_in_both_arrivals() {
        val before = grid("h" to android.telecom.Call.STATE_HOLDING, "a" to android.telecom.Call.STATE_ACTIVE)
        val after = grid("h" to android.telecom.Call.STATE_ACTIVE, "a" to android.telecom.Call.STATE_HOLDING)
        assertTrue(before.canSwap)
        assertTrue(after.canSwap)
        assertEquals("a", before.primary?.key)
        assertEquals("h", after.primary?.key)
    }

    @Test
    fun disconnecting_one_side_closes_the_swap_window() {
        val ended = grid("a" to android.telecom.Call.STATE_DISCONNECTED, "b" to android.telecom.Call.STATE_ACTIVE)
        assertFalse(ended.canSwap)
        assertEquals("b", ended.primary?.key)

        val bothEnded = grid("a" to android.telecom.Call.STATE_DISCONNECTED, "b" to android.telecom.Call.STATE_DISCONNECTED)
        assertTrue(bothEnded.isEmpty)
    }
}

/**
 * design.md §16, screening-timeout bullet — the JVM-executable half of
 * androidTest/…/ScreeningTimeoutFailOpenTest: XxCallScreeningService must give
 * up STRICTLY inside the platform's 5 s screening deadline (§4.2), because its
 * timeout IS the R9 fail-open. Guards against silent drift of SCREEN_BUDGET_MS.
 */
class ScreeningFailOpenBudgetTest {

    @Test
    fun screening_budget_stays_inside_platform_deadline() {
        // const in a private companion ⇒ static final on the outer Kotlin class.
        val budget = XxCallScreeningService::class.java
            .getDeclaredField("SCREEN_BUDGET_MS")
            .apply { isAccessible = true }
            .getLong(null)

        assertTrue(
            budget > 0 && budget < PLATFORM_DEADLINE_MS,
            "screening budget $budget ms must be positive and strictly inside the platform's $PLATFORM_DEADLINE_MS ms deadline",
        )
    }

    private companion object {
        const val PLATFORM_DEADLINE_MS = 5_000L // §4.2
    }
}
