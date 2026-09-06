package com.piercingxx.xxdialer.vvm

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.VoicemailContract
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Downloads a voicemail's audio from the carrier IMAP mailbox and stores it
 * on the [VoicemailContract] row, flipping HAS_CONTENT only when bytes
 * actually arrive. Host and credentials come from the last STATUS SMS —
 * never invented. Missing creds / host / UID / canSync ⇒ false, row stays
 * content-less.
 */
class VvmAudioDownloader(
    private val context: Context,
    private val loadCreds: () -> VvmSms? = {
        runBlocking { VvmCredentialStore(context).load() }
    },
    private val readSourceData: (Uri) -> String? = { uri ->
        context.contentResolver.query(
            uri,
            arrayOf(VoicemailContract.Voicemails.SOURCE_DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails.SOURCE_DATA))
        }
    },
    private val fetchAudio: (VvmSms, String) -> ByteArray? = { creds, uid ->
        VvmImapClient(creds).fetchAudio(
            uid = uid,
            cellularDataRequired = VvmImapTransport.cellularDataRequired(context),
            onCellularData = VvmImapTransport.onCellularData(context),
        )
    },
    private val storeAudio: (Uri, ByteArray) -> Boolean = { uri, bytes ->
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                true
            } ?: false
        }.getOrDefault(false)
        written && context.contentResolver.update(
            uri,
            ContentValues().apply { put(VoicemailContract.Voicemails.HAS_CONTENT, 1) },
            null,
            null,
        ) > 0
    },
    private val cellularDataRequired: () -> Boolean = {
        VvmImapTransport.cellularDataRequired(context)
    },
    private val onCellularData: () -> Boolean = {
        VvmImapTransport.onCellularData(context)
    },
) {

    /**
     * Fetches audio for [uri] and writes it. Returns true only when HAS_CONTENT
     * is now 1. Failures (no STATUS host, no UID, transport refused, IMAP
     * error) return false and leave the row untouched.
     */
    fun fetchAndStore(uri: Uri): Boolean {
        val creds = runCatching { loadCreds() }.getOrNull() ?: return false
        val host = creds.fields["srv"] ?: return false
        if (!VvmImapHostPolicy.canConnectTo(host)) return false
        if (!VvmImapPolicy.canSync(cellularDataRequired(), onCellularData())) return false
        val uid = runCatching { readSourceData(uri) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        val bytes = runCatching { fetchAudio(creds, uid) }.getOrNull() ?: return false
        if (bytes.isEmpty()) return false
        return runCatching { storeAudio(uri, bytes) }
            .onFailure { Log.w(TAG, "vvm audio store failed for $uri", it) }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "VvmAudioDownloader"
    }
}
