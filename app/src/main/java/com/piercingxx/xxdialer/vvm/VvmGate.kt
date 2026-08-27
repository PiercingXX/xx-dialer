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
}