package com.piercingxx.xxdialer.vvm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.VoicemailContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Non-exported handler for [VoicemailContract.ACTION_FETCH_VOICEMAIL]. The
 * audio fetcher used to broadcast into the void; this receiver (and the
 * in-process [handle] path) downloads via [VvmAudioDownloader] so HAS_CONTENT
 * can flip when IMAP actually returns audio.
 */
class VvmFetchHandler : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun handle(
            context: Context,
            intent: Intent,
            download: (Context, Uri) -> Boolean = { ctx, uri ->
                VvmAudioDownloader(ctx).fetchAndStore(uri)
            },
        ): Boolean {
            if (intent.action != VoicemailContract.ACTION_FETCH_VOICEMAIL) return false
            val uri = intent.data ?: return false
            return runCatching { download(context, uri) }.getOrDefault(false)
        }
    }
}
