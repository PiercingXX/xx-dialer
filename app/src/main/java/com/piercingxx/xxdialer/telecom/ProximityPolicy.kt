package com.piercingxx.xxdialer.telecom

/**
 * "Should the screen be blanked right now?" — the entire proximity decision,
 * as a pure function of (call phase, audio route), with no android.* in sight
 * so it is JVM-testable (ProximityPolicyTest).
 *
 * Isolating it is not tidiness: neither input can be produced without a real
 * call on a real SIM, so if the rule lived inline in the InCallService it
 * would be untestable in this tree — the exact place a "blanks on speaker"
 * regression hides until someone is staring at a dead screen mid-call.
 *
 * The rule itself is AOSP's, arrived at the same way: blank while the call is
 * off-hook and the audio is going to the ear, never while it is going
 * anywhere the user can plausibly be LOOKING at the phone.
 */

/**
 * Coarse per-call phase, mapped from `Call.STATE_*` at the service edge.
 *
 * Deliberately not [com.piercingxx.xxdialer.ui.Line]: that enum answers "what
 * does the call grid render", and it folds STATE_AUDIO_PROCESSING into ACTIVE
 * because the grid should show such a call. This enum answers "is audio in the
 * user's ear", which is a different question with a different answer for that
 * same state (§4.3 screening: no ear-side audio yet ⇒ [ENDED] bucket).
 */
enum class CallPhase {
    /** Ringing, not yet answered — the FSI answer surface must stay lit and touchable. */
    RINGING,

    /** Dialing/connecting: the user raises the phone before the far end picks up. */
    OUTGOING,

    ACTIVE,

    /** Parked leg: audio path is still the ear's, the call has not ended. */
    HELD,

    /** Disconnected, disconnecting, or anything else that is not a live leg. */
    ENDED,
}

/** Where the call's audio is currently going; mapped at the edge from either routing API. */
enum class EarRoute {
    EARPIECE,
    SPEAKER,
    WIRED_HEADSET,
    BLUETOOTH,
    STREAMING,

    /**
     * Telecom has pushed no audio state yet. Treated as EARPIECE below — see
     * [ProximityPolicy.isAgainstEar] for why that direction is the safe one.
     */
    UNKNOWN,
}

/** What the holder should do with the platform lock after a state or route change. */
enum class ProximityAction {
    /** Off-hook and against the ear: hold the lock, screen and touchscreen off. */
    HOLD,

    /**
     * Still on a call, but the audio just left the ear (speaker/headset/BT).
     * The user is looking at the screen RIGHT NOW — the display must come back
     * this instant, not when a sensor next reports far.
     */
    RELEASE_IMMEDIATE,

    /**
     * No live call left. The phone is most likely still against a face, so the
     * display is asked to stay off until the sensor reports far — releasing
     * outright here fires the screen into the user's eye at the exact moment
     * they lower the phone.
     */
    RELEASE_WHEN_FAR,
}

object ProximityPolicy {

    /**
     * The one decision. [phases] is every tracked leg's phase (call waiting
     * means more than one); [route] is where audio is going right now.
     */
    fun decide(phases: Collection<CallPhase>, route: EarRoute): ProximityAction = when {
        isOffHook(phases) && isAgainstEar(route) -> ProximityAction.HOLD
        isOffHook(phases) -> ProximityAction.RELEASE_IMMEDIATE
        else -> ProximityAction.RELEASE_WHEN_FAR
    }

    /**
     * Off-hook, not "has calls": a phone that is merely RINGING must not blank.
     * The ringing surface IS the answer surface (§12) — blanking it makes the
     * call unanswerable for as long as whatever is near the sensor stays near.
     *
     * Precedence, not any/all: a second call ringing during a live one (§12
     * call waiting) is still an off-hook phone at an ear, and the cheek that
     * is already there must keep being ignored. Off-hook wins.
     */
    fun isOffHook(phases: Collection<CallPhase>): Boolean =
        phases.any {
            it == CallPhase.ACTIVE || it == CallPhase.OUTGOING || it == CallPhase.HELD
        }

    /**
     * The exclusion that matters. Speaker, wired headset and Bluetooth all mean
     * the phone is not the thing at the user's ear, so the screen is live
     * furniture they are looking at and touching — blanking it there is the
     * single most common proximity bug in third-party dialers, and it is worse
     * than having no proximity handling at all. STREAMING (audio handed to
     * another device entirely) is the same case.
     *
     * UNKNOWN counts as the ear on purpose. It exists only in the sliver before
     * Telecom's first audio callback lands, the service re-reads the live
     * `callAudioState` before deciding anyway, and the earpiece is the platform
     * default for a voice call — so the alternative reading (refuse the lock)
     * would silently disable the whole feature on any device whose first
     * callback is late, to avoid a race that resolves itself milliseconds later
     * with a route change that releases the lock.
     */
    fun isAgainstEar(route: EarRoute): Boolean = when (route) {
        EarRoute.EARPIECE, EarRoute.UNKNOWN -> true
        EarRoute.SPEAKER, EarRoute.WIRED_HEADSET, EarRoute.BLUETOOTH, EarRoute.STREAMING -> false
    }
}
