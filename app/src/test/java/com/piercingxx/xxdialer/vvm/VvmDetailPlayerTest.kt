package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * The voicemail detail screen's action seams (todo.md VVM detail, T3): play/pause,
 * speaker, call back, and delete. This pins that every one of the four detail
 * actions is present and drives its real seam:
 * - play/pause drives a self-contained MediaPlayer (created, fed the content URI,
 *   started, then paused on a second tap);
 * - speaker routes the AudioManager to speakerphone on/off;
 * - call back invokes the dial seam with the caller's number;
 * - delete invokes the delete seam on the voicemail row's URI.
 * The dial and delete seams are injected so the test can observe them without
 * touching the telecom service or the provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmDetailPlayerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InMemoryVoicemailProvider.register()
    }

    @After
    fun tearDown() {
        // The create listener is static Robolectric state; clear it so it never
        // leaks into another test.
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test
    fun detailActionsPresent() {
        // --- Play/pause: the detail player owns a MediaPlayer and drives it. ---
        var created: ShadowMediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { _, shadow -> created = shadow }
        val uri = Uri.parse("content://com.piercingxx.xxdialer/voicemail/1")
        // Robolectric's ShadowMediaPlayer throws when setDataSource is given a
        // content URI with no registered media info; register it so the direct-play
        // path is actually exercised rather than swallowed by the runCatching.
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, uri),
            ShadowMediaPlayer.MediaInfo(1, 1000),
        )

        val player = VvmDetailPlayer(
            context = context,
            dial = { number -> dialed = number; true },
            deleteRow = { target -> deleted = target; true },
        )

        // Play starts the MediaPlayer on the voicemail's content URI.
        assertTrue("play must start playback", player.togglePlay(uri))
        assertNotNull("playback must create a MediaPlayer", created)
        assertEquals("the MediaPlayer must be fed the voicemail's content URI", uri, created!!.sourceUri)
        assertTrue("the voicemail must actually start playing", created!!.isReallyPlaying)

        // A second tap pauses.
        assertTrue("pause must return true", player.togglePlay(uri))
        assertFalse("a second tap must pause playback", created!!.isReallyPlaying)

        // --- Speaker: routes the AudioManager to speakerphone on/off. ---
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertTrue("speaker on must be applied", player.setSpeakerphone(true))
        assertTrue("the AudioManager must report speakerphone on", audioManager.isSpeakerphoneOn)
        assertTrue("speaker off must be applied", player.setSpeakerphone(false))
        assertFalse("the AudioManager must report speakerphone off", audioManager.isSpeakerphoneOn)

        // --- Call back: dials the caller's number. ---
        assertTrue("call back must place the call", player.callBack("+15551234567"))
        assertEquals("call back must dial the caller's number", "+15551234567", dialed)

        // --- Delete: removes the voicemail row. ---
        assertTrue("delete must remove the row", player.delete(uri))
        assertEquals("delete must target the voicemail row", uri, deleted)

        player.release()
    }

    @Test
    fun callBackRefusesBlankNumber() {
        val player = VvmDetailPlayer(
            context = context,
            dial = { _ -> dialed = "should not be called"; true },
            deleteRow = { _ -> true },
        )
        assertFalse("a blank number must not be dialed", player.callBack("   "))
        assertEquals("no dial may happen for a blank number", null, dialed)
    }

    private var dialed: String? = null
    private var deleted: Uri? = null
}