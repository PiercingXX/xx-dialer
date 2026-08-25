package com.piercingxx.xxphone.ring

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.BlockedNumberContract
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.piercingxx.xxphone.util.StarContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-addressed notification actions: Block (D6) performs the
 * BlockedNumberContract write under the default-dialer role; Ring next time
 * (§12) stars the contact behind the number. Both clear the card they rode
 * in on when the write lands.
 *
 * NON-EXPORTED BY DESIGN (B3): the block write is dialer-privileged and the
 * star write mutates the user's contacts, so the component must be
 * unreachable from third-party apps — only PendingIntents minted by this
 * app's own notifications can land here. The exported sibling
 * (MissedCallReceiver) deliberately carries ONLY Telecom's protected
 * SHOW_MISSED_CALLS broadcast; no privileged action is reachable through it.
 */
class BlockActionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intents.ACTION_BLOCK_NUMBER && action != Intents.ACTION_RING_NEXT_TIME) return
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    Intents.ACTION_BLOCK_NUMBER ->
                        block(
                            app,
                            intent.getStringExtra(Intents.EXTRA_BLOCK_NUMBER),
                            intent.getIntExtra(Intents.EXTRA_CANCEL_NOTIF_ID, NotifIds.MISSED),
                        )
                    Intents.ACTION_RING_NEXT_TIME ->
                        star(
                            app,
                            intent.getStringExtra(Intents.EXTRA_RING_NEXT_E164),
                            intent.getIntExtra(Intents.EXTRA_CANCEL_NOTIF_ID, NotifIds.MISSED),
                        )
                }
            } finally {
                result.finish()
            }
        }
    }

    /** Default-dialer role permits BlockedNumberContract writes (D6); best effort either way. */
    private fun block(context: Context, e164: String?, cancelNotifId: Int) {
        if (e164.isNullOrBlank()) return
        runCatching {
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, e164)
                },
            )
        } // role lost ⇒ insert refused; the user still sees the number in Recents (§15)
        NotificationManagerCompat.from(context).cancel(cancelNotifId) // the card it rode in on
    }

    /**
     * Ring next time (★): star the contact so RingPolicy row 5 rings it. A
     * number with no contact row cannot be starred — the toast says where to
     * recover (Recents row actions) instead of pretending it worked (§15).
     */
    private suspend fun star(context: Context, e164: String?, cancelNotifId: Int) {
        if (e164.isNullOrBlank()) return
        val ok = StarContact.ringNextTime(context, e164)
        if (ok) NotificationManagerCompat.from(context).cancel(cancelNotifId)
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (ok) "Will ring next time ★" else "No contact to star — use Recents row actions",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
