package com.piercingxx.xxphone.ring

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B2: the notification id vocabulary must stay pairwise-disjoint — a shared
 * id lets one stream's notify/cancel evict another's card, and a silenced
 * call's answerable surfacing may never be lost (R7). Values are frozen:
 * notifications posted by older installs still carry them.
 */
class NotifIdsTest {

    @Test
    fun `notification ids are pairwise disjoint`() {
        val ids = listOf(NotifIds.INCOMING, NotifIds.ONGOING, NotifIds.MISSED, NotifIds.SILENCED)
        assertEquals(ids.size, ids.toSet().size, "id collision ⇒ one stream evicts another (B2)")
    }

    @Test
    fun `literals stay pinned`() {
        assertEquals(1, NotifIds.INCOMING)
        assertEquals(2, NotifIds.ONGOING)
        assertEquals(1001, NotifIds.MISSED)
        assertEquals(1100, NotifIds.SILENCED)
    }
}
