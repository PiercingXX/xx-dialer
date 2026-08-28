package com.piercingxx.xxdialer.vvm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.VoicemailContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads a user action on a voicemail row back to the carrier mailbox over IMAP
 * (todo.md VVM V8, T2). Deleting a voicemail, or playing one (which marks it seen),
 * must be reflected to the carrier so the server-side message state stays in sync
 * with what the user did locally. This uploader owns that round-trip:
 *
 * - [VvmUploadPolicy.route] decides which carrier upload a delete/seen user action
 *   demands (DELETE, MARK_SEEN, or NONE).
 * - [VvmUploadPolicy.carrierMessageId] maps the local row's `_ID` to the carrier
 *   message identity the command targets.
 * - [sendCommand] is the injectable IMAP-command seam: given the carrier message id
 *   and the action, it sends the command to the mailbox and reports whether the
 *   carrier accepted it. The production wiring (T3) supplies the real IMAP client.
 * - Only after the carrier accepts the upload does the uploader reflect the result
 *   into VoicemailContract: a DELETE removes the row, a MARK_SEEN marks it read.
 *
 * The sendCommand seam is a suspend function because IMAP is socket I/O; the whole
 * upload runs on Dispatchers.IO so no network or provider I/O ever touches the main
 * thread. Failure direction (§15): when the carrier refuses the command the local
 * row is left untouched and [upload] returns false, so the caller can surface an
 * honest "could not sync to carrier" state instead of silently diverging.
 */
class VvmMailboxUploader(
    private val context: Context,
    private val sendCommand: suspend (messageId: String, action: VvmUploadPolicy.UploadAction) -> Boolean,
) {

    /**
     * Uploads the user action on the voicemail row [rowId] to the carrier and, once
     * the carrier accepts it, reflects the result into VoicemailContract. Returns
     * true when the action was uploaded and reflected, false when the carrier
     * refused the command or the local reflect failed. A no-op action (neither
     * delete nor seen) needs no upload and returns true.
     */
    suspend fun upload(rowId: Long, delete: Boolean, markSeen: Boolean): Boolean {
        val action = VvmUploadPolicy.route(delete, markSeen)
        if (action == VvmUploadPolicy.UploadAction.NONE) return true
        return withContext(Dispatchers.IO) {
            val messageId = VvmUploadPolicy.carrierMessageId(rowId)
            val accepted = sendCommand(messageId, action)
            if (!accepted) return@withContext false
            reflect(action, rowId)
        }
    }

    private fun reflect(action: VvmUploadPolicy.UploadAction, rowId: Long): Boolean {
        val rowUri = ContentUris.withAppendedId(
            VoicemailContract.Voicemails.buildSourceUri(context.packageName),
            rowId,
        )
        val resolver = context.contentResolver
        return when (action) {
            VvmUploadPolicy.UploadAction.DELETE ->
                runCatching { resolver.delete(rowUri, null, null) > 0 }
                    .onFailure { Log.w(LOG_TAG, "vvm upload: could not delete row $rowId", it) }
                    .getOrDefault(false)
            VvmUploadPolicy.UploadAction.MARK_SEEN ->
                runCatching {
                    val values = ContentValues().apply {
                        put(VoicemailContract.Voicemails.IS_READ, 1)
                    }
                    resolver.update(rowUri, values, null, null) > 0
                }
                    .onFailure { Log.w(LOG_TAG, "vvm upload: could not mark row $rowId seen", it) }
                    .getOrDefault(false)
            VvmUploadPolicy.UploadAction.NONE -> true
        }
    }

    private companion object {
        const val LOG_TAG = "VvmMailboxUploader"
    }
}