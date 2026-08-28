package com.piercingxx.xxdialer.vvm

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.piercingxx.xxdialer.telecom.CallManager

/**
 * The voicemail detail screen's action seams (todo.md VVM detail, T3): play/pause,
 * speaker on/off, call back, and delete. The detail is a self-contained player —
 * it owns its own [MediaPlayer] and drives playback directly from the voicemail's
 * content URI, rather than depending on the V6 fetch-then-play audio pipeline
 * (which is not landed). The list/detail UI (V7) calls these four methods when the
 * user taps the corresponding controls.
 *
 * Every Android-touching behaviour is an injectable seam with a live default, so
 * the class is JVM-testable without Robolectric shadowing the whole stack:
 * - [mediaPlayer] is the playback engine [togglePlay] drives.
 * - [speaker] is the speakerphone seam [setSpeakerphone] routes through.
 * - [dial] is the call-back seam, defaulting to [CallManager.place] (the app's only
 *   outgoing-call path — never ACTION_CALL).
 * - [deleteRow] is the delete seam, defaulting to a VoicemailContract row delete.
 *
 * Failure direction (§15): every action returns false rather than crashing when its
 * seam fails, and logs the reason, so the detail screen can surface an honest
 * "could not do that" state instead of dying.
 */
class VvmDetailPlayer(
    private val context: Context,
    private val mediaPlayer: MediaPlayer = MediaPlayer(),
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
    private val speaker: (Boolean) -> Boolean = { on ->
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = on
        true
    },
    private val dial: (String) -> Boolean = { number ->
        val activity = context as? Activity
        activity != null && CallManager.place(activity, number)
    },
    private val deleteRow: (Uri) -> Boolean = { uri ->
        context.contentResolver.delete(uri, null, null) > 0
    },
) {

    /**
     * Toggles playback of the voicemail at [uri]: starts playing when it is not
     * playing, pauses when it is. Returns true when the state change was applied,
     * false when the row could not be played (e.g. no content) or paused.
     */
    fun togglePlay(uri: Uri): Boolean {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            return true
        }
        return playDirectly(uri)
    }

    /**
     * Routes playback through the speakerphone ([on] true) or the earpiece
     * ([on] false). Returns true when the mode was applied, false when the audio
     * service refused it.
     */
    fun setSpeakerphone(on: Boolean): Boolean = runCatching { speaker(on) }
        .onFailure { Log.w(TAG, "vvm detail: could not set speakerphone=$on", it) }
        .getOrDefault(false)

    /**
     * Dials the caller's [number] back. Returns true when the call was placed,
     * false when the number is blank or the call could not be placed.
     */
    fun callBack(number: String): Boolean {
        if (number.isBlank()) return false
        return dial(number)
    }

    /**
     * Deletes the voicemail row at [uri]. Returns true when a row was deleted,
     * false when nothing was removed.
     */
    fun delete(uri: Uri): Boolean = deleteRow(uri)

    /** Releases the underlying [MediaPlayer] — call when the detail screen closes. */
    fun release() {
        runCatching { mediaPlayer.release() }
    }

    private fun playDirectly(uri: Uri): Boolean =
        runCatching {
            mediaPlayer.reset()
            // Audio attributes are a preference, not a precondition: setting them
            // can fail on some platforms (Robolectric has no shadow for
            // MediaPlayer.setAudioAttributes), and that must not abort playback.
            runCatching {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }.onFailure { Log.w(TAG, "vvm detail: could not set audio attributes for $uri", it) }
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.prepare()
            mediaPlayer.start()
            true
        }.onFailure { Log.w(TAG, "vvm detail: could not play $uri", it) }.getOrDefault(false)

    private companion object {
        const val TAG = "VvmDetailPlayer"
    }
}