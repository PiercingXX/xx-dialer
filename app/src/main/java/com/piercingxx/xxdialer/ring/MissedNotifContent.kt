package com.piercingxx.xxdialer.ring

/**
 * Copy + style for the missed-call notification. Count==1 keeps the annotated
 * line; count>1 is a short newest-first list, not a lifetime total as the
 * product UI. Pure so the InboxStyle decision is JVM-testable.
 */
object MissedNotifContent {

    const val INBOX_LIMIT = 5

    fun title(count: Int): String = if (count > 1) "$count missed calls" else "Missed call"

    /**
     * Telecom's EXTRA_NOTIFICATION_COUNT is a lifetime total on GrapheneOS
     * when cancelMissedCallsNotification does not reset it. Prefer the number
     * of missed rows since Recents was last opened; if that query is empty,
     * this broadcast is still one new event — never the lifetime dump.
     */
    fun displayCount(telecomCount: Int, unseenMissed: Int): Int {
        if (unseenMissed > 0) return unseenMissed
        return 1
    }

    fun usesInboxStyle(count: Int): Boolean = count > 1

    /** Newest-first display lines, already ordered by the caller. */
    fun inboxLines(entries: List<String>, limit: Int = INBOX_LIMIT): List<String> =
        entries.filter { it.isNotBlank() }.take(limit)
}
