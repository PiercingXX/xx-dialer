package com.piercingxx.xxdialer.ring

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The extra literals are cross-component contracts (§12): ring/ mints them in
 * notification PendingIntents, telecom/ui read them — and notifications created
 * by older installs still carry them, so they must never drift.
 */
class IntentsContractTest {

    @Test
    fun `filter extra literal stays pinned`() {
        assertEquals("com.piercingxx.xxdialer.extra.FILTER_E164", Intents.EXTRA_FILTER_E164)
        assertEquals("com.piercingxx.xxdialer.extra.RECENTS_FILTER", Intents.EXTRA_RECENTS_FILTER)
        assertEquals("missed", Intents.RECENTS_FILTER_MISSED)
    }

    @Test
    fun `block action and extras stay pinned (B3 split contract)`() {
        assertEquals("com.piercingxx.xxdialer.action.BLOCK_NUMBER", Intents.ACTION_BLOCK_NUMBER)
        assertEquals("com.piercingxx.xxdialer.extra.BLOCK_E164", Intents.EXTRA_BLOCK_NUMBER)
        assertEquals("com.piercingxx.xxdialer.extra.CALLBACK_E164", Intents.EXTRA_CALLBACK_E164)
    }
}
