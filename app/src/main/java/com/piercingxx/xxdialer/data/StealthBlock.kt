package com.piercingxx.xxdialer.data

/**
 * The reserved "Blocked" group: no ring, no incoming card, no Recents row,
 * no missed banner. CallLog still keeps the rows so Contacts can show them
 * on that person's card after an explicit search.
 */
object StealthBlock {

    const val GROUP = "Blocked"

    fun isGroup(name: String): Boolean =
        name.trim().equals(GROUP, ignoreCase = true) ||
            name.trim().equals("block", ignoreCase = true)

    suspend fun lookupKeys(db: XxDatabase): Set<String> =
        runCatching { db.tierMemberDao().keysFor(GROUP).toSet() }.getOrDefault(emptySet())

    suspend fun hiddenE164s(db: XxDatabase): Set<String> {
        val keys = lookupKeys(db)
        if (keys.isEmpty()) return emptySet()
        return runCatching { db.contactMirrorDao().all() }
            .getOrDefault(emptyList())
            .mapNotNull { row -> if (row.lookupKey in keys) row.e164 else null }
            .toSet()
    }

    suspend fun isHiddenNumber(db: XxDatabase, e164: String?): Boolean {
        if (e164.isNullOrEmpty()) return false
        return e164 in hiddenE164s(db)
    }
}
