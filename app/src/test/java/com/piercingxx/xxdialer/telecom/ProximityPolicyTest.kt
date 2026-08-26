package com.piercingxx.xxdialer.telecom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Should the screen be blanked right now?" — the whole proximity rule, on
 * the JVM.
 *
 * This suite is the only place either half of the decision can be exercised
 * in this tree: call state needs a real call and audio route needs a real
 * route, and there is no SIM in any test device to produce either. The rule
 * was therefore written as a pure function precisely so that the two bugs
 * that matter — blanking on speaker, and never releasing — are provable
 * without a phone call.
 */
class ProximityPolicyTest {

    private val ear = EarRoute.EARPIECE

    // ---- off-hook at the ear: blank -----------------------------------------

    @Test
    fun active_call_on_earpiece_holds_the_lock() {
        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(listOf(CallPhase.ACTIVE), ear))
    }

    @Test
    fun outgoing_call_holds_before_the_far_end_answers() {
        // The phone goes to the ear while it is still ringing at the other end.
        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(listOf(CallPhase.OUTGOING), ear))
    }

    @Test
    fun held_call_still_holds_the_lock() {
        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(listOf(CallPhase.HELD), ear))
    }

    @Test
    fun unknown_route_is_treated_as_the_earpiece() {
        // Pre-first-callback sliver only; the earpiece is the platform default.
        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(listOf(CallPhase.ACTIVE), EarRoute.UNKNOWN))
    }

    // ---- ringing is not off-hook ----------------------------------------------

    @Test
    fun ringing_alone_never_blanks_the_answer_surface() {
        assertEquals(
            ProximityAction.RELEASE_WHEN_FAR,
            ProximityPolicy.decide(listOf(CallPhase.RINGING), ear),
        )
        assertFalse(ProximityPolicy.isOffHook(listOf(CallPhase.RINGING)))
    }

    @Test
    fun a_second_call_ringing_during_a_live_one_still_blanks() {
        // §12 call waiting: off-hook wins — the cheek is already on the glass.
        assertEquals(
            ProximityAction.HOLD,
            ProximityPolicy.decide(listOf(CallPhase.ACTIVE, CallPhase.RINGING), ear),
        )
    }

    // ---- the route exclusion (the bug this exists to prevent) -----------------

    @Test
    fun speaker_never_blanks_while_the_call_is_live() {
        assertEquals(
            ProximityAction.RELEASE_IMMEDIATE,
            ProximityPolicy.decide(listOf(CallPhase.ACTIVE), EarRoute.SPEAKER),
        )
    }

    @Test
    fun wired_headset_never_blanks_while_the_call_is_live() {
        assertEquals(
            ProximityAction.RELEASE_IMMEDIATE,
            ProximityPolicy.decide(listOf(CallPhase.ACTIVE), EarRoute.WIRED_HEADSET),
        )
    }

    @Test
    fun bluetooth_never_blanks_while_the_call_is_live() {
        assertEquals(
            ProximityAction.RELEASE_IMMEDIATE,
            ProximityPolicy.decide(listOf(CallPhase.ACTIVE), EarRoute.BLUETOOTH),
        )
    }

    @Test
    fun streamed_audio_never_blanks_while_the_call_is_live() {
        assertEquals(
            ProximityAction.RELEASE_IMMEDIATE,
            ProximityPolicy.decide(listOf(CallPhase.ACTIVE), EarRoute.STREAMING),
        )
    }

    @Test
    fun every_route_is_classified_and_only_the_ear_qualifies() {
        val againstEar = EarRoute.entries.filter(ProximityPolicy::isAgainstEar).toSet()
        assertEquals(setOf(EarRoute.EARPIECE, EarRoute.UNKNOWN), againstEar)
    }

    @Test
    fun every_phase_is_classified_and_only_live_legs_are_off_hook() {
        val offHook = CallPhase.entries.filter { ProximityPolicy.isOffHook(listOf(it)) }.toSet()
        assertEquals(setOf(CallPhase.ACTIVE, CallPhase.OUTGOING, CallPhase.HELD), offHook)
    }

    // ---- release flavours ------------------------------------------------------

    @Test
    fun no_calls_at_all_releases_waiting_for_the_sensor() {
        // Call over: the phone is probably still on a face — do not fire the
        // display into the user's eye before they lower it.
        assertEquals(ProximityAction.RELEASE_WHEN_FAR, ProximityPolicy.decide(emptyList(), ear))
    }

    @Test
    fun only_dead_legs_left_releases_waiting_for_the_sensor() {
        assertEquals(
            ProximityAction.RELEASE_WHEN_FAR,
            ProximityPolicy.decide(listOf(CallPhase.ENDED, CallPhase.ENDED), ear),
        )
    }

    @Test
    fun ended_call_on_speaker_still_releases_rather_than_holding() {
        assertEquals(
            ProximityAction.RELEASE_WHEN_FAR,
            ProximityPolicy.decide(listOf(CallPhase.ENDED), EarRoute.SPEAKER),
        )
    }

    @Test
    fun leaving_the_ear_mid_call_releases_immediately_not_when_far() {
        // The distinction IS the fix: the user just tapped Speaker and is
        // looking at the screen; waiting on a sensor leaves it dark in their hand.
        val live = listOf(CallPhase.ACTIVE)
        assertEquals(ProximityAction.RELEASE_IMMEDIATE, ProximityPolicy.decide(live, EarRoute.SPEAKER))
        assertTrue(ProximityPolicy.decide(live, EarRoute.SPEAKER) != ProximityAction.RELEASE_WHEN_FAR)
    }

    // ---- a whole call, as a sequence of decisions -------------------------------

    @Test
    fun full_call_arc_holds_releases_and_reacquires_correctly() {
        val dialing = listOf(CallPhase.OUTGOING)
        val live = listOf(CallPhase.ACTIVE)
        val gone = listOf(CallPhase.ENDED)

        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(dialing, ear))
        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(live, ear))
        // speaker on
        assertEquals(ProximityAction.RELEASE_IMMEDIATE, ProximityPolicy.decide(live, EarRoute.SPEAKER))
        // speaker off again — back to the ear, back to blanking
        assertEquals(ProximityAction.HOLD, ProximityPolicy.decide(live, ear))
        // hung up
        assertEquals(ProximityAction.RELEASE_WHEN_FAR, ProximityPolicy.decide(gone, ear))
        assertEquals(ProximityAction.RELEASE_WHEN_FAR, ProximityPolicy.decide(emptyList(), ear))
    }
}
