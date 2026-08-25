package com.piercingxx.xxdialer.probe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

object RingPoster {

    private const val CHANNEL_ID = "ring_probe_v1"
    private const val NOTIFICATION_ID = 1001
    private const val ACTION_ANSWER_TAP = "com.piercingxx.xxdialer.probe.ring.ANSWER"
    private const val ACTION_DECLINE_TAP = "com.piercingxx.xxdialer.probe.ring.DECLINE"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Ring probe", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "WS0 channel-ring probe"
            setSound(
                Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
        ProbeLog.log(
            "channel_ring",
            "event" to "channel_ensured",
            "channel_id" to CHANNEL_ID,
            "requested_importance" to "HIGH",
            "requested_sound" to Settings.System.DEFAULT_RINGTONE_URI.toString(),
            "audio_usage" to "USAGE_NOTIFICATION_RINGTONE",
            "vibration" to true,
            "note" to "channels are immutable-after-creation; recreate with same id does NOT reset settings"
        )
        describe(context)
    }

    fun describe(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel == null) {
            ProbeLog.log("channel_ring", "event" to "channel_state", "channel_id" to CHANNEL_ID, "state" to "absent")
            return
        }
        ProbeLog.log(
            "channel_ring",
            "event" to "channel_state",
            "channel_id" to channel.id,
            "importance" to importanceName(channel.importance),
            "stored_sound" to (channel.sound?.toString() ?: "null"),
            "vibration_enabled" to channel.shouldVibrate(),
            "audio_usage" to (channel.audioAttributes?.usage ?: -1)
        )
    }

    fun post(context: Context, source: String) {
        ProbeLog.init(context)
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) {
            ProbeLog.log("channel_ring", "event" to "post_blocked", "source" to source, "reason" to "notifications_disabled")
            return
        }
        ensureChannel(context)
        val caller = Person.Builder()
            .setName("XX Probe Caller")
            .setUri("tel:+15550100")
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentTitle("XX-Probe incoming call")
            .setContentText("channel-ring test — should play the current Phone ringtone")
            .setOngoing(true)
            .setFullScreenIntent(tapIntent(context, ACTION_FULL_SCREEN_TAP), true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    tapIntent(context, ACTION_DECLINE_TAP),
                    tapIntent(context, ACTION_ANSWER_TAP)
                )
            )
            .build()
        try {
            notifications.notify(NOTIFICATION_ID, notification)
            ProbeLog.log(
                "channel_ring",
                "event" to "call_style_posted",
                "source" to source,
                "channel_id" to CHANNEL_ID,
                "can_use_full_screen_intent" to notifications.canUseFullScreenIntent(),
                "expectation" to "ring with current Settings-Sound-Phone ringtone via DEFAULT_RINGTONE_URI indirection"
            )
        } catch (t: SecurityException) {
            ProbeLog.log("channel_ring", "event" to "post_failed", "source" to source, "error" to "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun consumeTap(intent: Intent?, context: Context) {
        when (intent?.action) {
            ACTION_ANSWER_TAP -> {
                ProbeLog.event("channel_ring", "event=user_tap tap=answer")
                cancel(context)
            }
            ACTION_DECLINE_TAP -> {
                ProbeLog.event("channel_ring", "event=user_tap tap=decline")
                cancel(context)
            }
            ACTION_FULL_SCREEN_TAP -> ProbeLog.event("channel_ring", "event=user_tap tap=full_screen_intent")
        }
    }

    private const val ACTION_FULL_SCREEN_TAP = "com.piercingxx.xxdialer.probe.ring.FULL_SCREEN"

    private fun tapIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            action.hashCode(),
            Intent(action).setClass(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun importanceName(importance: Int): String = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> "NONE"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        else -> "VALUE($importance)"
    }
}
