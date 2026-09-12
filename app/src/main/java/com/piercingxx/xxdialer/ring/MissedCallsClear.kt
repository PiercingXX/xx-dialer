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
        markViewed(context)
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

    fun markViewed(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_VIEWED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Epoch millis Recents was last shown; 0 before the first clear. */
    fun viewedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_VIEWED_AT, 0L)

    private const val PREFS = "xx_dialer_missed"
    private const val KEY_VIEWED_AT = "viewed_at"
}
