package com.piercingxx.xxdialer.vvm

import android.content.ContentValues
import android.content.Context
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * The audio player (todo.md VVM audio, T3) must play a content-bearing voicemail
 * directly and fetch-then-play one that carries no content. This pins the whole
 * fetch-vs-play rule end to end: a row is inserted into VoicemailContract,
 * [VvmAudioPlayer] reads it, and either a MediaPlayer is created and started on
 * the content URI (direct play) or ACTION_FETCH_VOICEMAIL is broadcast for the
 * row (fetch-then-play).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmAudioPlayerTest {

    @Before
    fun setUp() {
        // Robolectric has no working VoicemailContract provider, so readHasContent()
        // returns null and play() can never reach the direct-play or fetch branch.
        // Register an in-memory provider so the insert/query round-trip exercises
        // the real production read path (see InMemoryVoicemailProvider).
        InMemoryVoicemailProvider.register()
    }

    @After
    fun tearDown() {
        // The create listener is static Robolectric state; clear it so it never
        // leaks into another test.
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test
    fun playsContentOrFetchesThenPlays() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Direct play: HASCONTENT=1 → a MediaPlayer is created, fed the content
        // URI, and started.
        var created: ShadowMediaPlayer? = null
        ShadowMediaPlayer.setCreateListener { _, shadow -> created = shadow }
        val contentUri = insertVoicemail(context, hasContent = 1)
        // Robolectric's ShadowMediaPlayer throws when setDataSource is given a
        // content URI with no registered media info (it has no real provider to
        // read from). Register the row's URI so prepare()/start() can succeed and
        // the direct-play path is actually exercised rather than swallowed by the
        // player's runCatching.
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, contentUri),
            ShadowMediaPlayer.MediaInfo(1, 1000),
        )
        val played = VvmAudioPlayer(context).play(contentUri)
        assertTrue("a content-bearing voicemail must play directly", played)
        assertNotNull("playback must create a MediaPlayer", created)
        assertEquals(
            "the MediaPlayer must be fed the voicemail's content URI",
            contentUri,
            created!!.sourceUri,
        )
        assertTrue("the content-bearing voicemail must actually start playing", created!!.isReallyPlaying)

        // Fetch-then-play: HASCONTENT=0 → the audio is fetched (an
        // ACTION_FETCH_VOICEMAIL broadcast is sent) before playback.
        val fetchUri = insertVoicemail(context, hasContent = 0)
        val fetched = VvmAudioPlayer(context).play(fetchUri)
        assertTrue("a content-less voicemail must fetch before playing", fetched)
        val broadcast = shadowOf(context.applicationContext as android.app.Application)
            .broadcastIntents
            .single { it.action == VoicemailContract.ACTION_FETCH_VOICEMAIL }
        assertEquals(
            "the fetch broadcast must target the voicemail row",
            fetchUri,
            broadcast.data,
        )
    }

    private fun insertVoicemail(context: Context, hasContent: Int): android.net.Uri {
        val resolver = context.contentResolver
        val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        val values = ContentValues().apply {
            put(VoicemailContract.Voicemails.NUMBER, "+15551234567")
            put(VoicemailContract.Voicemails.DATE, 1_700_000_000_000L)
            put(VoicemailContract.Voicemails.HAS_CONTENT, hasContent)
        }
        return resolver.insert(sourceUri, values)!!
    }
}