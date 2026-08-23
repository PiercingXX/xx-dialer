package com.piercingxx.xxphone.ring

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.BlockedNumberContract
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Self-addressed Block action (D6): performs the BlockedNumberContract write
 * under the default-dialer role and clears the missed-call card it rode in on.
 *
 * NON-EXPORTED BY DESIGN (B3): this write is dialer-privileged, so the
 * component must be unreachable from third-party apps — only PendingIntents
 * minted by this app's own notifications can land here. The exported sibling
 * (MissedCallReceiver) deliberately carries ONLY Telecom's protected
 * SHOW_MISSED_CALLS broadcast; no privileged action is reachable through it.
 */
class BlockActionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intents.ACTION_BLOCK_NUMBER) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block(context.applicationContext, intent.getStringExtra(Intents.EXTRA_BLOCK_NUMBER))
            } finally {
                result.finish()
            }
        }
    }

    /** Default-dialer role permits BlockedNumberContract writes (D6); best effort either way. */
    private fun block(context: Context, e164: String?) {
        if (e164.isNullOrBlank()) return
        runCatching {
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, e164)
                },
            )
        } // role lost ⇒ insert refused; the user still sees the number in Recents (§15)
        NotificationManagerCompat.from(context).cancel(NotifIds.MISSED)
    }
}
