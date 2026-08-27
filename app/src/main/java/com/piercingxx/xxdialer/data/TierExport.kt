package com.piercingxx.xxdialer.data

/**
 * Contract TxxT (and any other same-signature family app) uses to read the
 * Business tier and its window. The membership lives in this app's Room
 * because ContactsContract.Groups cannot carry it on GrapheneOS (D4); the
 * window is the Rules screen's `business_window`. Both have to be readable
 * out of process or SMS cannot honour the same 09:00–19:00 schedule.
 *
 * Signature permission [PERMISSION] is the only gate — no INTERNET, no
 * public dump of the address book.
 */
object TierExport {

    const val AUTHORITY = "com.piercingxx.xxdialer.tier"
    const val PERMISSION = "com.piercingxx.xxdialer.permission.TIER_SYNC"

    const val PATH_WINDOW = "window"
    const val PATH_BIZ = "biz"

    const val WINDOW_URI = "content://$AUTHORITY/$PATH_WINDOW"
    const val BIZ_URI = "content://$AUTHORITY/$PATH_BIZ"

    const val COL_START_MINUTE = "start_minute"
    const val COL_END_MINUTE = "end_minute"
    const val COL_DAYS_MASK = "days_mask"
    const val COL_LOOKUP_KEY = "lookup_key"

    val WINDOW_COLUMNS = arrayOf(COL_START_MINUTE, COL_END_MINUTE, COL_DAYS_MASK)
    val BIZ_COLUMNS = arrayOf(COL_LOOKUP_KEY)

    const val DEFAULT_START_MINUTE = 9 * 60
    const val DEFAULT_END_MINUTE = 19 * 60
    const val DEFAULT_DAYS_MASK = 0b1111111

    fun windowValues(startMinute: Int, endMinute: Int, daysMask: Int): Array<Any> =
        arrayOf(startMinute, endMinute, daysMask)
}
