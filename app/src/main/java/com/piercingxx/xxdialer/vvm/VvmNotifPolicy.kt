package com.piercingxx.xxdialer.vvm

/**
 * New-voicemail notification gate and channel identity (todo.md V2 / V9).
 * Notify only while the VVM toggle is on. Channel `voicemail_v1` at SECRET
 * importance (raw 1000 — [android.app.NotificationManager.IMPORTANCE_SECRET]
 * was removed at compileSdk 35).
 */
object VvmNotifPolicy {

    const val CHANNEL_ID = "voicemail_v1"

    /** SECRET; the named constant is gone on SDK 35. */
    const val CHANNEL_IMPORTANCE = 1000

    /** Pairwise-disjoint from ring ids (1, 2, 1001, 1100). */
    const val NOTIFICATION_ID_BASE = 1200

    fun shouldNotify(toggleOn: Boolean): Boolean = toggleOn

    fun notificationId(rowHint: Int): Int = NOTIFICATION_ID_BASE + (rowHint and 0x0fff)
}
