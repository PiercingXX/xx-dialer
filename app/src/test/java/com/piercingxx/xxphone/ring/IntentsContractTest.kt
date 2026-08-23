package com.piercingxx.xxphone.ring

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
        assertEquals("com.piercingxx.xxphone.extra.FILTER_E164", Intents.EXTRA_FILTER_E164)
    }

    @Test
    fun `block action and extras stay pinned (B3 split contract)`() {
        assertEquals("com.piercingxx.xxphone.action.BLOCK_NUMBER", Intents.ACTION_BLOCK_NUMBER)
        assertEquals("com.piercingxx.xxphone.extra.BLOCK_E164", Intents.EXTRA_BLOCK_NUMBER)
        assertEquals("com.piercingxx.xxphone.extra.CALLBACK_E164", Intents.EXTRA_CALLBACK_E164)
    }
}
