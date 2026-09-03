package com.piercingxx.xxdialer.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.piercingxx.xxdialer.core.RingRepeat
import com.piercingxx.xxdialer.core.RingRepeatPolicy

/**
 * Plays the ringtone on the ringer stream for [RingRepeat.TWICE] and
 * [RingRepeat.UNTIL_VOICEMAIL]. [RingRepeat.ONCE] stays on the CallStyle
 * channel — that is the platform's one-shot, and we do not second-guess it.
 *
 * Ringtone + USAGE_NOTIFICATION_RINGTONE, not MediaPlayer: DND, ringer mode
 * and volume stay the platform's (design D2). The CallStyle card for a
 * repeating ring is posted on the silent channel so the tone is not doubled.
 */
class CallRinger(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val stopSelf = Runnable { stop() }

    fun start(toneUri: Uri, repeat: RingRepeat) {
        stop()
        if (!RingRepeatPolicy.insistent(repeat)) return
        val tone = runCatching { RingtoneManager.getRingtone(context, toneUri) }.getOrNull()
            ?: return
        runCatching {
            tone.audioAttributes = RING_ATTRIBUTES
            tone.isLooping = true
        }.onFailure { Log.w(TAG, "ringer attributes failed", it) }
        ringtone = tone
        runCatching { tone.play() }
            .onFailure { Log.w(TAG, "ringer play failed", it) }
        RingRepeatPolicy.silenceAfterMs(repeat, toneDurationMs(context, toneUri))
            ?.let { handler.postDelayed(stopSelf, it) }
    }

    fun stop() {
        handler.removeCallbacks(stopSelf)
        val playing = ringtone
        ringtone = null
        runCatching { playing?.stop() }
        runCatching { playing?.isLooping = false }
    }

    companion object {
        private const val TAG = "CallRinger"
        const val FALLBACK_TONE_MS = 8_000L

        private val RING_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun toneDurationMs(context: Context, uri: Uri): Long {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: FALLBACK_TONE_MS
            } catch (_: Throwable) {
                FALLBACK_TONE_MS
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
