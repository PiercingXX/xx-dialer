package com.piercingxx.xxphone.ring

/**
 * Cross-component intent literals (§12): one definition here, minted by
 * ring/ and read by ui/. These were previously pinned as three private string
 * twins that could drift apart silently.
 */
object Intents {

    /**
     * Recents deep-link filter: open Recents pre-filtered to this E.164 —
     * the "Ring next time" recovery action's stopgap (§12) and the People
     * sheet's history row. The literal is frozen: notifications created by
     * older installs still carry it.
     */
    const val EXTRA_FILTER_E164 = "com.piercingxx.xxphone.extra.FILTER_E164"

    /**
     * Block action executed by the NON-EXPORTED sibling receiver
     * (BlockActionsReceiver, B3): the BlockedNumberContract write is
     * dialer-privileged, so only this app's own PendingIntents may land it.
     */
    const val ACTION_BLOCK_NUMBER = "com.piercingxx.xxphone.action.BLOCK_NUMBER"

    /** E.164 payload of [ACTION_BLOCK_NUMBER]. */
    const val EXTRA_BLOCK_NUMBER = "com.piercingxx.xxphone.extra.BLOCK_E164"

    /** E.164 handed to CallbackTrampolineActivity for a placeCall() callback (§4.1). */
    const val EXTRA_CALLBACK_E164 = "com.piercingxx.xxphone.extra.CALLBACK_E164"
}
