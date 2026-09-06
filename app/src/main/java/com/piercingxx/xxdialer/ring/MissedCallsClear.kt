package com.piercingxx.xxdialer.ring

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat

/**
 * Recents is the product UI for missed calls. Opening it (or the Missed chip)
 * must clear Telecom's badge, mark viewed CallLog rows, and drop our own
 * [NotifIds.MISSED] card — otherwise EXTRA_NOTIFICATION_COUNT is a lifetime
 * total that never resets.
 */
object MissedCallsClear {

    /** CallLog rows the user has now seen: still NEW, missed or rejected. */
    const val VIEWED_SELECTION: String =
        "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} IN (?, ?)"

    fun viewedArgs(): Array<String> = arrayOf(
        CallLog.Calls.MISSED_TYPE.toString(),
        CallLog.Calls.REJECTED_TYPE.toString(),
    )

    fun viewedValues(): ContentValues = ContentValues().apply {
        put(CallLog.Calls.NEW, 0)
    }

    /**
     * Recents on screen, or the Missed filter becoming active, both mean the
     * user has looked at missed calls. Pure so the Recents call sites stay a
     * one-liner and the rule is unit-testable without Telecom.
     */
    fun shouldClear(recentsVisible: Boolean, missedFilterActive: Boolean): Boolean =
        recentsVisible || missedFilterActive

    fun clear(context: Context) {
        runCatching {
            context.getSystemService(TelecomManager::class.java)
                ?.cancelMissedCallsNotification()
        }
        runCatching {
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                viewedValues(),
                VIEWED_SELECTION,
                viewedArgs(),
            )
        }
        runCatching {
            NotificationManagerCompat.from(context).cancel(NotifIds.MISSED)
        }
    }
}
