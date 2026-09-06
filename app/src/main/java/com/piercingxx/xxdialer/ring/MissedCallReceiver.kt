package com.piercingxx.xxdialer.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.data.ScreenLogEntity
import com.piercingxx.xxdialer.telecom.LogRows
import com.piercingxx.xxdialer.ui.RecentsActivity
import com.piercingxx.xxdialer.util.E164
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
        val policy = runCatching {
            ServiceLocator.settings(context).silencedNotifPolicy()
        }.getOrDefault(POLICY_IMMEDIATE)

        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 1)
        val rawNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        val e164 = rawNumber?.let { E164.normalize(it) }

        val logRow = e164?.let { number ->
            runCatching { ServiceLocator.db(context).screenLogDao().latestFor(number) }.getOrNull()
        }

        if (!MissedNotifPolicy.shouldPost(policy, logRow?.verdict, logRow?.mode)) return

        val inboxLines = if (MissedNotifContent.usesInboxStyle(count)) {
            queryRecentMissedLabels(context, MissedNotifContent.INBOX_LIMIT)
        } else {
            emptyList()
        }
        val notification = buildNotification(context, count, rawNumber ?: e164, logRow, inboxLines)
        // POST_NOTIFICATIONS may be ungranted; a refused card must not crash.
        try {
            NotificationManagerCompat.from(context).notify(NotifIds.MISSED, notification)
        } catch (se: SecurityException) {
            Log.w(TAG, "missed-call post refused", se)
        }
    }

    private fun buildNotification(
        context: Context,
        count: Int,
        number: String?,
        logRow: ScreenLogEntity?,
        inboxLines: List<String>,
    ): Notification = NotificationCompat.Builder(context, ensureChannel(context))
        .setSmallIcon(android.R.drawable.stat_notify_missed_call)
        .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
        .setAutoCancel(true)
        .setContentTitle(MissedNotifContent.title(count))
        .setContentIntent(contentIntent(context))
        .apply {
            if (MissedNotifContent.usesInboxStyle(count)) {
                val lines = MissedNotifContent.inboxLines(inboxLines)
                setContentText(lines.firstOrNull() ?: "Tap to open Recents")
                val inbox = NotificationCompat.InboxStyle()
                lines.forEach { inbox.addLine(it) }
                setStyle(inbox)
            } else {
                setContentText(textFor(count, number, logRow))
                setStyle(NotificationCompat.BigTextStyle().bigText(bigTextFor(count, number, logRow)))
                if (number != null) addActionsFor(context, number)
            }
        }
        .build()

    /**
     * Newest-first labels for the count>1 InboxStyle. Cached name when the
     * platform has one, otherwise the raw number. Fail empty — never invent.
     */
    private fun queryRecentMissedLabels(context: Context, limit: Int): List<String> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
        )
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val numberCol = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val name = c.getString(nameCol)?.takeIf { it.isNotBlank() }
                        val number = c.getString(numberCol)?.takeIf { it.isNotBlank() }
                        (name ?: number)?.let { add(it) }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

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
        // The log stores the verdict token; LogRows owns the display word so
        // this notification and the Rules screen cannot drift apart.
        val verdictWord = LogRows.dispositionWord(row.verdict)
        return listOfNotNull(number, verdictWord, row.reason.trim()).joinToString(" · ")
    }

    private fun NotificationCompat.Builder.addActionsFor(context: Context, e164: String) {
        addAction(buildIconlessAction(callBackPendingIntent(context, e164), "Call back"))
        addAction(buildIconlessAction(ringNextTimePendingIntent(context, e164), "Ring next time"))
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
     * "Ring next time (★)" stars the contact in one tap (§12) — routed to
     * the NON-EXPORTED sibling receiver (B3), same self-addressed
     * discipline as Block.
     */
    private fun ringNextTimePendingIntent(context: Context, e164: String): PendingIntent? =
        runCatching {
            PendingIntent.getBroadcast(
                context,
                REQUEST_RING_NEXT_TIME,
                Intent(context, BlockActionsReceiver::class.java)
                    .setAction(Intents.ACTION_RING_NEXT_TIME)
                    .putExtra(Intents.EXTRA_RING_NEXT_E164, e164)
                    .putExtra(Intents.EXTRA_CANCEL_NOTIF_ID, NotifIds.MISSED),
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
                    .putExtra(Intents.EXTRA_CANCEL_NOTIF_ID, NotifIds.MISSED)
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
                Intent(context, RecentsActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                    .putExtra(Intents.EXTRA_RECENTS_FILTER, Intents.RECENTS_FILTER_MISSED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    companion object {
        private const val TAG = "MissedCallReceiver"
        private const val CHANNEL_ID = "missed_v1"
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_CALL_BACK = 2
        private const val REQUEST_RING_NEXT_TIME = 3
        private const val REQUEST_BLOCK = 4

        /** Telecom's number extra on ACTION_SHOW_MISSED_CALLS_NOTIFICATION. */
        private const val EXTRA_PHONE_NUMBER = "android.telecom.extra.PHONE_NUMBER"

        const val POLICY_IMMEDIATE = "immediate"
        const val POLICY_NEVER = "never"
        const val POLICY_DAILY = "daily"
    }
}

/**
 * `"never"` is for the silenced category only. A call that actually rang
 * (including observed-silence) still posts the missed-call card.
 */
internal object MissedNotifPolicy {
    fun shouldPost(policy: String, verdict: String?, mode: String?): Boolean {
        if (policy != MissedCallReceiver.POLICY_NEVER) return true
        val silenced = LogRows.dispositionWord(verdict) == "Silenced"
        val observed = mode == LogRows.modeName(com.piercingxx.xxdialer.core.Mode.OBSERVING)
        return !silenced || observed
    }
}
