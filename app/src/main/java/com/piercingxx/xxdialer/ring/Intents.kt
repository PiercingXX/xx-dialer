package com.piercingxx.xxdialer.ring

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
    const val EXTRA_FILTER_E164 = "com.piercingxx.xxdialer.extra.FILTER_E164"

    /**
     * Recents chip pre-filter from the missed-call notification: value is
     * [RECENTS_FILTER_MISSED] so Recents opens on the Missed chip, not All.
     */
    const val EXTRA_RECENTS_FILTER = "com.piercingxx.xxdialer.extra.RECENTS_FILTER"

    /** [EXTRA_RECENTS_FILTER] value that selects the Missed chip. */
    const val RECENTS_FILTER_MISSED = "missed"

    /**
     * Block action executed by the NON-EXPORTED sibling receiver
     * (BlockActionsReceiver, B3): the BlockedNumberContract write is
     * dialer-privileged, so only this app's own PendingIntents may land it.
     */
    const val ACTION_BLOCK_NUMBER = "com.piercingxx.xxdialer.action.BLOCK_NUMBER"

    /** E.164 payload of [ACTION_BLOCK_NUMBER]. */
    const val EXTRA_BLOCK_NUMBER = "com.piercingxx.xxdialer.extra.BLOCK_E164"

    /** E.164 handed to CallbackTrampolineActivity for a placeCall() callback (§4.1). */
    const val EXTRA_CALLBACK_E164 = "com.piercingxx.xxdialer.extra.CALLBACK_E164"

    /**
     * Ring-next-time star write (§12), executed by the NON-EXPORTED sibling
     * receiver (BlockActionsReceiver, B3) — same self-addressed-only
     * discipline as the block write.
     */
    const val ACTION_RING_NEXT_TIME = "com.piercingxx.xxdialer.action.RING_NEXT_TIME"

    /** E.164 payload of [ACTION_RING_NEXT_TIME]. */
    const val EXTRA_RING_NEXT_E164 = "com.piercingxx.xxdialer.extra.RING_NEXT_E164"

    /** Notification id the action rode in on — cancelled when the write lands. */
    const val EXTRA_CANCEL_NOTIF_ID = "com.piercingxx.xxdialer.extra.CANCEL_NOTIF_ID"
}
