package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ring.Intents
import com.piercingxx.xxdialer.ui.RecentsMerge.Filter

/**
 * Recents filter-chip mapping: layout ids, the missed-notification extra, and
 * [Filter]. Pure besides the generated R.id constants so unit tests can pin
 * the extra → Missed chip path without inflating the activity.
 */
object RecentsFilterIntent {

    fun filterForChipId(chipId: Int?): Filter = when (chipId) {
        R.id.recents_chip_missed -> Filter.MISSED
        R.id.recents_chip_silenced -> Filter.SILENCED
        R.id.recents_chip_blocked -> Filter.BLOCKED
        else -> Filter.ALL
    }

    fun chipIdFor(filter: Filter): Int = when (filter) {
        Filter.ALL -> R.id.recents_chip_all
        Filter.MISSED -> R.id.recents_chip_missed
        Filter.SILENCED -> R.id.recents_chip_silenced
        Filter.BLOCKED -> R.id.recents_chip_blocked
    }

    fun filterOf(extra: String?): Filter? = when (extra) {
        Intents.RECENTS_FILTER_MISSED -> Filter.MISSED
        else -> null
    }
}
