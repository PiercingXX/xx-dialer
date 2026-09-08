package com.piercingxx.xxdialer.data

/**
 * Contract TxxT and XX-Contacts use for the Business tier and its window.
 * Membership lives in this app's Room because ContactsContract.Groups cannot
 * carry it on GrapheneOS (D4); the window is the Rules screen's
 * `business_window`. Family apps read and write membership by LOOKUP_KEY;
 * the window stays query-only.
 *
 * Signature permission [PERMISSION] is the only gate — no INTERNET, no
 * public dump of the address book.
 */
object TierExport {

    const val AUTHORITY = "com.piercingxx.xxdialer.tier"
    const val PERMISSION = "com.piercingxx.xxdialer.permission.TIER_SYNC"

    const val PATH_WINDOW = "window"
    const val PATH_BIZ = "biz"
    const val PATH_GROUPS = "groups"

    const val WINDOW_URI = "content://$AUTHORITY/$PATH_WINDOW"
    const val BIZ_URI = "content://$AUTHORITY/$PATH_BIZ"
    const val GROUPS_URI = "content://$AUTHORITY/$PATH_GROUPS"

    const val COL_START_MINUTE = "start_minute"
    const val COL_END_MINUTE = "end_minute"
    const val COL_DAYS_MASK = "days_mask"
    const val COL_LOOKUP_KEY = "lookup_key"
    const val COL_GROUP_NAME = "group_name"

    val WINDOW_COLUMNS = arrayOf(COL_START_MINUTE, COL_END_MINUTE, COL_DAYS_MASK)
    val BIZ_COLUMNS = arrayOf(COL_LOOKUP_KEY)
    val GROUP_COLUMNS = arrayOf(COL_GROUP_NAME, COL_LOOKUP_KEY)

    fun isReservedGroup(name: String): Boolean = when (name.trim().lowercase()) {
        "", "star", "biz", "business" -> true
        else -> false
    }

    fun groupTier(name: String): String? {
        val n = name.trim()
        if (isReservedGroup(n)) return null
        return n
    }

    const val DEFAULT_START_MINUTE = 9 * 60
    const val DEFAULT_END_MINUTE = 19 * 60
    const val DEFAULT_DAYS_MASK = 0b1111111

    const val BIZ_DELETE_SELECTION = "$COL_LOOKUP_KEY = ?"

    fun windowValues(startMinute: Int, endMinute: Int, daysMask: Int): Array<Any> =
        arrayOf(startMinute, endMinute, daysMask)

    fun bizInsertPairs(lookupKey: String): Map<String, String> =
        mapOf(COL_LOOKUP_KEY to lookupKey)

    fun bizDeleteArgs(lookupKey: String): Array<String> = arrayOf(lookupKey)
}
