package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.VoicemailContract
import android.util.Log

/**
 * Fetches a voicemail's audio from the carrier (todo.md VVM audio, T2). A
 * voicemail row that carries no audio content (HASCONTENT == 0) must be fetched
 * by broadcasting [VoicemailContract.ACTION_FETCH_VOICEMAIL] for its row URI; one
 * that already has content is played directly and must never be re-fetched. The
 * fetch-vs-play decision lives in [VvmAudioFetchPolicy] and is reused here so the
 * single rule stays in one place.
 */
class VvmAudioFetcher(private val context: Context) {

    /**
     * Reads HASCONTENT from the voicemail row at [uri]; when it is false, asks the
     * platform to fetch the audio by broadcasting ACTION_FETCH_VOICEMAIL for [uri].
     * Returns true when a fetch was triggered, false when the voicemail already
     * carries content (or the row could not be read).
     */
    fun fetchIfMissingContent(uri: Uri): Boolean {
        val hasContent = readHasContent(uri) ?: return false
        if (!VvmAudioFetchPolicy.shouldFetch(hasContent)) return false

        val intent = Intent(VoicemailContract.ACTION_FETCH_VOICEMAIL).setData(uri)
        context.sendBroadcast(intent)
        return true
    }

    /**
     * Reads the HASCONTENT flag for the voicemail row at [uri], or null when the
     * row cannot be read (missing, or the provider is unavailable).
     */
    private fun readHasContent(uri: Uri): Boolean? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(VoicemailContract.Voicemails.HAS_CONTENT), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    cursor.getInt(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails.HAS_CONTENT)) != 0
                }
        }.onFailure { Log.w(LOG_TAG, "vvm audio fetch: could not read HASCONTENT for $uri", it) }.getOrNull()

    private companion object {
        const val LOG_TAG = "VvmAudioFetcher"
    }
}