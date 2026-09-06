package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ring.Intents
import com.piercingxx.xxdialer.ui.RecentsMerge.Filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentsFilterIntentTest {

    @Test
    fun missedNotificationExtraSelectsTheMissedChip() {
        assertEquals(Filter.MISSED, RecentsFilterIntent.filterOf(Intents.RECENTS_FILTER_MISSED))
        assertEquals(R.id.recents_chip_missed, RecentsFilterIntent.chipIdFor(Filter.MISSED))
        assertNull(RecentsFilterIntent.filterOf(null))
        assertNull(RecentsFilterIntent.filterOf("all"))
    }

    @Test
    fun chipIdsMapToExclusiveFilters() {
        assertEquals(Filter.ALL, RecentsFilterIntent.filterForChipId(R.id.recents_chip_all))
        assertEquals(Filter.MISSED, RecentsFilterIntent.filterForChipId(R.id.recents_chip_missed))
        assertEquals(Filter.SILENCED, RecentsFilterIntent.filterForChipId(R.id.recents_chip_silenced))
        assertEquals(Filter.BLOCKED, RecentsFilterIntent.filterForChipId(R.id.recents_chip_blocked))
        assertEquals(Filter.ALL, RecentsFilterIntent.filterForChipId(null))
    }
}
