package com.piercingxx.xxdialer.vvm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ui.VoicemailActivity

/**
 * Posts a `voicemail_v1` SECRET-channel card for each newly written voicemail
 * row. Toggle-off is a no-op ([VvmNotifPolicy.shouldNotify]). Tap opens the
 * Voicemail tab. POST_NOTIFICATIONS refusal is swallowed, never thrown.
 */
class VvmVoicemailNotifier(private val context: Context) {

    fun notifyNewVoicemail(toggleOn: Boolean, caller: String?, timestampMillis: Long) {
        if (!VvmNotifPolicy.shouldNotify(toggleOn)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, VoicemailActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = caller?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.vvm_unknown_caller)
        val card = NotificationCompat.Builder(context, VvmNotifPolicy.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_voicemail)
            .setContentTitle(context.getString(R.string.vvm_notif_title))
            .setContentText(context.getString(R.string.vvm_notif_text, text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        val id = VvmNotifPolicy.notificationId(
            (caller.hashCode() xor timestampMillis.toInt()),
        )
        try {
            NotificationManagerCompat.from(context).notify(id, card)
        } catch (se: SecurityException) {
            Log.w(TAG, "voicemail notification refused", se)
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(VvmNotifPolicy.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                VvmNotifPolicy.CHANNEL_ID,
                context.getString(R.string.vvm_notif_channel),
                VvmNotifPolicy.CHANNEL_IMPORTANCE,
            ),
        )
    }

    private companion object {
        const val TAG = "VvmVoicemailNotifier"
    }
}
