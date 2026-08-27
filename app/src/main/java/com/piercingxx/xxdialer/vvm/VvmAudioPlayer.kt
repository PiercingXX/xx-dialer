package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * Plays a voicemail's audio (todo.md VVM audio, T3). A voicemail that already
 * carries content (HASCONTENT == 1) is played directly with a [MediaPlayer]; one
 * that does not is fetched from the carrier first (fetch-then-play) via
 * [VvmAudioFetcher], which broadcasts ACTION_FETCH_VOICEMAIL so the platform
 * delivers the audio. The fetch-vs-play decision lives in [VvmAudioFetchPolicy]
 * and is shared with the fetcher so the single rule stays in one place.
 */
class VvmAudioPlayer(private val context: Context) {

    private val fetcher = VvmAudioFetcher(context)

    /**
     * Plays the voicemail at [uri]. Returns true when playback was started, or
     * when a fetch was triggered so playback can start once the audio arrives;
     * false when the row could not be read or playback failed.
     */
    fun play(uri: Uri): Boolean {
        val hasContent = fetcher.readHasContent(uri) ?: return false
        return if (VvmAudioFetchPolicy.shouldFetch(hasContent)) {
            // Fetch-then-play: the audio is not here yet — ask the carrier to
            // deliver it; playback continues once the content arrives (V7 UI).
            fetcher.fetchIfMissingContent(uri)
        } else {
            playDirectly(uri)
        }
    }

    private fun playDirectly(uri: Uri): Boolean =
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(context, uri)
                prepare()
                start()
            }
            true
        }.onFailure { Log.w(LOG_TAG, "vvm audio play: could not play $uri", it) }.getOrDefault(false)

    private companion object {
        const val LOG_TAG = "VvmAudioPlayer"
    }
}