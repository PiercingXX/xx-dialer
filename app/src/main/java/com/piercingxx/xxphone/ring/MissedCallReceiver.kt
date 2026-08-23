package com.piercingxx.xxphone.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.piercingxx.xxphone.ServiceLocator
import com.piercingxx.xxphone.data.ScreenLogEntity
import com.piercingxx.xxphone.ui.RecentsActivity
import com.piercingxx.xxphone.util.E164
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns Telecom's missed-call notification so it can carry the screening
 * reason (design §13, R7): "→ Silenced · Unknown, outside 09–17", with the
 * recovery affordances inline (§12): Call back · Ring next time · Block.
 *
 * B3 EXPORTED-SURFACE CONTRACT: this component is exported and handles ONLY
 * Telecom's `ACTION_SHOW_MISSED_CALLS_NOTIFICATION` — a protected broadcast
 * sent solely by Telecom (uid system), so third-party apps cannot forge it,
 * and no privileged action is reachable through this receiver. The Block
 * write (BlockedNumberContract under dialer privilege) lives exclusively in
 * the NON-EXPORTED sibling [BlockActionsReceiver].
 *
 * Fail direction (§15): a missing log row posts a plain missed call — no
 * invented reasons — and every platform refusal is swallowed, not thrown.
 */
class MissedCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(context.applicationContext, intent)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION ->
                postMissedNotification(context, intent)
        }
    }

    private suspend fun postMissedNotification(context: Context, intent: Intent) {
        // §12 three-state policy; "never" means this card never exists.
        val policy = runCatching {
            ServiceLocator.settings(context).silencedNotifPolicy()
        }.getOrDefault(POLICY_IMMEDIATE)
        if (policy == POLICY_NEVER) return

        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 1)
        // TelecomManager.EXTRA_PHONE_NUMBER is not in the public SDK surface;
        // this is its literal value on the SHOW_MISSED_CALLS broadcast.
        val rawNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        val e164 = rawNumber?.let { E164.normalize(it) }

        val logRow = e164?.let { number ->
            runCatching { ServiceLocator.db(context).screenLogDao().latestFor(number) }.getOrNull()
        }

        val notification = buildNotification(context, count, rawNumber ?: e164, logRow)
        // POST_NOTIFICATIONS may be ungranted; a refused card must not crash.
        runCatching { NotificationManagerCompat.from(context).notify(NotifIds.MISSED, notification) }
    }

    private fun buildNotification(
        context: Context,
        count: Int,
        number: String?,
        logRow: ScreenLogEntity?,
    ): Notification = NotificationCompat.Builder(context, ensureChannel(context))
        .setSmallIcon(android.R.drawable.stat_notify_missed_call)
        .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
        .setAutoCancel(true)
        .setContentTitle(if (count > 1) "$count missed calls" else "Missed call") // burst batching §12
        .setContentText(textFor(count, number, logRow))
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextFor(count, number, logRow)))
        .setContentIntent(contentIntent(context))
        .apply {
            if (count == 1 && number != null) addActionsFor(context, number)
        }
        .build()

    private fun textFor(count: Int, number: String?, row: ScreenLogEntity?): String =
        bigTextFor(count, number, row).replace('\n', ' ')

    /**
     * Annotated form for a known number: "number · Silenced · Unknown,
     * outside 09–17" (§13). No log row — platform-blocked upstream, batched
     * burst — posts plain wording with no invented reasons (§15).
     */
    private fun bigTextFor(count: Int, number: String?, row: ScreenLogEntity?): String {
        if (count > 1 || number == null) return "Tap to open Recents"
        if (row == null || row.reason.isNullOrBlank()) return number // plain missed call
        val verdictWord = verdictWord(row.verdict)
        return listOfNotNull(number, verdictWord, row.reason.trim()).joinToString(" · ")
    }

    /** Log stores the verdict token; the display word follows §6's printed wording. */
    private fun verdictWord(verdictToken: String?): String? = when {
        verdictToken == null -> null
        verdictToken.startsWith("silence", ignoreCase = true) -> "Silenced"
        verdictToken.startsWith("block", ignoreCase = true) -> "Blocked"
        verdictToken.startsWith("ring", ignoreCase = true) -> "Rang"
        else -> null
    }

    private fun NotificationCompat.Builder.addActionsFor(context: Context, e164: String) {
        addAction(buildIconlessAction(callBackPendingIntent(context, e164), "Call back"))
        addAction(buildIconlessAction(rulesPendingIntent(context), "Ring next time"))
        addAction(buildIconlessAction(blockPendingIntent(context, e164), "Block"))
    }

    private fun buildIconlessAction(pi: PendingIntent?, title: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, pi).build()

    private fun callBackPendingIntent(context: Context, e164: String): PendingIntent? =
        runCatching {
            // L3: placeCall() everywhere (§4.1) — the tap lands in the
            // trampoline that routes through telecom.CallManager.place.
            PendingIntent.getActivity(
                context,
                REQUEST_CALL_BACK,
                Intent(context, CallbackTrampolineActivity::class.java)
                    .putExtra(Intents.EXTRA_CALLBACK_E164, e164),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    /**
     * "Ring next time (stars)" lives on the Rules screen until wave 2 wires
     * the star write from notifications; opening Rules is the honest stopgap.
     */
    private fun rulesPendingIntent(context: Context): PendingIntent? =
        runCatching {
            PendingIntent.getActivity(
                context,
                REQUEST_RING_NEXT_TIME,
                Intent(context, RecentsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    /** Block write routed to the NON-EXPORTED sibling (B3): privileged, self-addressed only. */
    private fun blockPendingIntent(context: Context, e164: String): PendingIntent? =
        runCatching {
            PendingIntent.getBroadcast(
                context,
                REQUEST_BLOCK,
                Intent(context, BlockActionsReceiver::class.java)
                    .setAction(Intents.ACTION_BLOCK_NUMBER)
                    .putExtra(Intents.EXTRA_BLOCK_NUMBER, e164),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    private fun ensureChannel(context: Context): String {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Missed calls",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        return CHANNEL_ID
    }

    private fun contentIntent(context: Context): PendingIntent? =
        runCatching {
            PendingIntent.getActivity(
                context,
                REQUEST_CONTENT,
                Intent(context, RecentsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    companion object {
        private const val CHANNEL_ID = "missed_v1"
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_CALL_BACK = 2
        private const val REQUEST_RING_NEXT_TIME = 3
        private const val REQUEST_BLOCK = 4

        /** Telecom's number extra on ACTION_SHOW_MISSED_CALLS_NOTIFICATION. */
        private const val EXTRA_PHONE_NUMBER = "android.telecom.extra.PHONE_NUMBER"

        private const val POLICY_IMMEDIATE = "immediate"
        private const val POLICY_NEVER = "never"
    }
}
