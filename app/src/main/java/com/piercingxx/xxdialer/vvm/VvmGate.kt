package com.piercingxx.xxdialer.vvm

/**
 * Opt-in gate for Visual Voicemail (todo.md D1/D4). VVM runs only while the
 * user's toggle is ON — the dialer holds no network sockets and never
 * ACTIVATEs the mailbox otherwise (D4: Telephony may still bind the default
 * dialer; we must not activate). Pure decision: T2 ships the toggle clause;
 * T3 adds the carrier-config validity clause.
 */
object VvmGate {

    /**
     * True only when the toggle is on. Toggle off ⇒ false, so the service
     * no-ops every callback and finishes its task immediately.
     */
    fun shouldRunVvm(toggleOn: Boolean): Boolean = toggleOn

    /**
     * True only when the toggle is on AND the carrier declares a VVM protocol
     * (todo.md D5: the protocol comes from CarrierConfig `KEY_VVM_TYPE_STRING`).
     * A carrier with no valid config cannot be ACTIVATEd, so this stays false
     * even when the toggle is on — [onCellServiceConnected] must not send
     * ACTIVATE unless both hold.
     */
    fun shouldActivate(toggleOn: Boolean, carrierConfigValid: Boolean): Boolean =
        toggleOn && carrierConfigValid

    /**
     * True only when the toggle has been turned OFF after a prior activation —
     * the "toggle off after on" path (todo.md "Turning off after it was on").
     * DEACTIVATE, unregister the voicemail source, and drop provider rows only
     * when we previously ACTIVATEd and the user has now switched off; a toggle
     * that was never on must not DEACTIVATE (nothing to undo), and a toggle
     * still on must not tear the mailbox down.
     */
    fun shouldDeactivate(toggleOn: Boolean, previouslyActivated: Boolean): Boolean =
        !toggleOn && previouslyActivated
}