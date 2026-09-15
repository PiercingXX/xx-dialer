package com.piercingxx.xxdialer.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import com.piercingxx.xxdialer.log.AppLog

/**
 * Plays the Telecom disconnect tone after the Call is already gone.
 * STREAM_VOICE_CALL plus MODE_IN_COMMUNICATION keeps it in the earpiece
 * the user still has at their ear; we restore the previous mode on stop.
 *
 * Constructor must not touch [Context.getApplicationContext]: Telecom
 * instantiates [InCallActivity] before attach, and field initializers run
 * then. AudioManager is resolved on first [start].
 */
class SupervisoryTone(private val context: Context) {

    private val audio: AudioManager by lazy {
        context.applicationContext.getSystemService(AudioManager::class.java)
    }
    private var generator: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null
    private var savedMode: Int? = null
    private var playing: Int? = null

    fun start(tone: Int) {
        if (tone <= 0) {
            stop()
            return
        }
        if (playing == tone && generator != null) return
        stop()
        runCatching {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focus = req
            audio.requestAudioFocus(req)
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME)
            generator = tg
            tg.startTone(tone, TONE_MS)
            playing = tone
        }.onFailure {
            AppLog.w("call", "busy tone failed", it)
            stop()
        }
    }

    fun stop() {
        runCatching { generator?.stopTone() }
        runCatching { generator?.release() }
        generator = null
        focus?.let { req -> runCatching { audio.abandonAudioFocusRequest(req) } }
        focus = null
        savedMode?.let { runCatching { audio.mode = it } }
        savedMode = null
        playing = null
    }

    private companion object {
        const val VOLUME = 90
        const val TONE_MS = 30_000
    }
}
