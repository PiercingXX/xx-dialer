package com.piercingxx.xxdialer.vvm

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.VoicemailContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A voicemail message fetched from the carrier mailbox, ready to be written as a
 * row into VoicemailContract (todo.md D3). [number] is the caller, [timestampMillis]
 * when the message was left, [durationSeconds] its length, [isRead] whether the
 * user has listened, and [transcription] the carrier's text/plain transcription
 * when it is provided (D9: show, never transcribe ourselves).
 */
data class VvmMailboxMessage(
    val number: String,
    val timestampMillis: Long,
    val durationSeconds: Int,
    val isRead: Boolean,
    val transcription: String? = null,
)

/**
 * The IMAP sync worker (todo.md T3 / V5). Runs the mailbox fetch OFF the main
 * thread under a PARTIAL_WAKE_LOCK and writes the fetched messages as rows into
 * VoicemailContract (D3: the platform provider is the store — never a Room table
 * of audio).
 *
 * The [fetch] seam is injectable so the unit test can drive the off-main-thread
 * and write-to-provider invariants without a real IMAP socket; the production
 * wiring (XxVisualVoicemailService) supplies the real IMAP client fetch (T5).
 * Every path releases the wake lock exactly once, in a finally.
 */
class VvmImapSyncWorker(
    private val context: Context,
    private val fetch: suspend (VvmSms) -> List<VvmMailboxMessage>,
) {

    /**
     * Runs one mailbox sync: acquires the wake lock, fetches the messages off the
     * main thread, and writes each as a VoicemailContract row. Returns the row URI
     * of every written message so the caller can fetch the missing audio for each
     * ([VvmAudioFetcher] — IMAP delivers metadata, never the audio itself). The
     * whole body runs on Dispatchers.IO so no socket or provider I/O ever touches
     * the main thread.
     */
    suspend fun sync(creds: VvmSms): List<Uri> {
        return withContext(Dispatchers.IO) {
            val lock = acquireWakeLock()
            try {
                writeRows(fetch(creds))
            } finally {
                releaseWakeLock(lock)
            }
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val power = context.applicationContext.getSystemService(PowerManager::class.java)
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
            ?.apply { acquire() }
    }.onFailure { Log.w(LOG_TAG, "vvm wake lock acquire failed", it) }.getOrNull()

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.w(LOG_TAG, "vvm wake lock release failed", it) }
    }

    private fun writeRows(messages: List<VvmMailboxMessage>): List<Uri> {
        val resolver = context.contentResolver
        val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        return messages.mapNotNull { message ->
            val values = ContentValues().apply {
                put(VoicemailContract.Voicemails.NUMBER, message.number)
                put(VoicemailContract.Voicemails.DATE, message.timestampMillis)
                put(VoicemailContract.Voicemails.DURATION, message.durationSeconds)
                put(VoicemailContract.Voicemails.IS_READ, if (message.isRead) 1 else 0)
                put(VoicemailContract.Voicemails.HAS_CONTENT, 0)
                message.transcription?.let {
                    put(VoicemailContract.Voicemails.TRANSCRIPTION, it)
                }
            }
            resolver.insert(sourceUri, values)
        }
    }

    private companion object {
        const val LOG_TAG = "VvmImapSyncWorker"
        const val LOCK_TAG = "com.piercingxx.xxdialer:vvm-sync"
    }
}