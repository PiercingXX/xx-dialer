package com.piercingxx.xxdialer.vvm

import android.content.ContentValues
import android.content.Context
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The audio fetcher (todo.md VVM audio, T2) must broadcast
 * ACTION_FETCH_VOICEMAIL for a voicemail row whose HASCONTENT is 0, and must NOT
 * broadcast for one that already carries content. This pins the fetch-vs-play
 * rule end to end: a row is inserted into VoicemailContract, [VvmAudioFetcher]
 * reads it, and the broadcast is observed on the application's shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmAudioFetcherTest {

    /**
     * Robolectric's in-memory VoicemailContract provider is a per-sandbox
     * singleton whose rows persist across tests in the same run. A row written
     * by an earlier test (VvmImapSyncWorkerTest, or a sibling test here) breaks
     * the row-URI query this class relies on, so the full-suite gate fails even
     * though each test passes in isolation. Clear every row before each test so
     * each one starts from a clean provider.
     */
    @Before
    fun clearVoicemailRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.delete(
            VoicemailContract.Voicemails.buildSourceUri(context.packageName),
            null,
            null,
        )
    }

    @Test
    fun broadcastsFetchWhenNoContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertVoicemail(context, hasContent = 0)

        val fetched = VvmAudioFetcher(context).fetchIfMissingContent(uri)

        assertTrue("a content-less voicemail must trigger a fetch", fetched)
        val broadcast = shadowOf(context.applicationContext as android.app.Application)
            .broadcastIntents
            .single { it.action == VoicemailContract.ACTION_FETCH_VOICEMAIL }
        assertEquals(
            "the fetch broadcast must target ACTION_FETCH_VOICEMAIL",
            VoicemailContract.ACTION_FETCH_VOICEMAIL,
            broadcast.action,
        )
        assertEquals("the fetch broadcast must carry the voicemail row URI", uri, broadcast.data)
    }

    @Test
    fun doesNotBroadcastWhenContentPresent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertVoicemail(context, hasContent = 1)

        val fetched = VvmAudioFetcher(context).fetchIfMissingContent(uri)

        assertFalse("a voicemail that already has content must not be fetched", fetched)
        val fetchBroadcasts = shadowOf(context.applicationContext as android.app.Application)
            .broadcastIntents
            .filter { it.action == VoicemailContract.ACTION_FETCH_VOICEMAIL }
        assertTrue(
            "no fetch broadcast may be sent for a content-bearing voicemail",
            fetchBroadcasts.isEmpty(),
        )
    }

    /**
     * Pins the live wiring: the IMAP sync worker writes each fetched message as a
     * VoicemailContract row with HASCONTENT 0 and returns its URI; the service
     * then hands that URI to [VvmAudioFetcher], which must broadcast
     * ACTION_FETCH_VOICEMAIL for it. This is the exact path
     * XxVisualVoicemailService.onSmsReceived uses, so it fails if the fetcher is
     * ever unreachable from the running application.
     */
    @Test
    fun syncRowIsFetchedAfterWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = VvmImapSyncWorker(context) {
            listOf(VvmMailboxMessage(number = "+15551234567", timestampMillis = 1_700_000_000_000L, durationSeconds = 42, isRead = false))
        }

        val written = runBlocking {
            worker.sync(VvmSms(type = "STATUS", fields = mapOf("srv" to "mail.example.com")))
        }

        assertTrue("sync must return the written row URIs for the fetcher", written.isNotEmpty())
        // IMAP delivers message metadata only — the written row must carry
        // HASCONTENT 0, which is exactly why the fetcher must broadcast a fetch.
        val rowUri = written.first()
        val resolver = context.contentResolver
        // A null cursor here means the row was not queryable — that is a failure,
        // not a case to pass silently. checkNotNull both fails on null AND smart-casts
        // the non-null cursor (a plain JUnit assertNotNull has no Kotlin contract,
        // so it cannot smart-cast; and org.junit.Assert.fail returns Unit, so an
        // Elvis `?: fail(...)` would not smart-cast either); deliberately NOT `?.`,
        // which would silently skip the HASCONTENT check.
        val cursor = checkNotNull(
            resolver.query(rowUri, arrayOf(VoicemailContract.Voicemails.HAS_CONTENT), null, null, null),
        ) { "the written voicemail row must be queryable" }
        cursor.use {
            assertTrue("the written voicemail row must be queryable", it.moveToFirst())
            assertEquals(
                "a synced (IMAP-delivered) voicemail must carry HASCONTENT 0",
                0,
                it.getInt(it.getColumnIndexOrThrow(VoicemailContract.Voicemails.HAS_CONTENT)),
            )
        }
        val fetched = VvmAudioFetcher(context).fetchIfMissingContent(rowUri)
        assertTrue("a synced (content-less) voicemail must trigger a fetch", fetched)
        val broadcast = shadowOf(context.applicationContext as android.app.Application)
            .broadcastIntents
            .single { it.action == VoicemailContract.ACTION_FETCH_VOICEMAIL }
        assertEquals(
            "the fetch broadcast must target the synced voicemail row",
            written.first(),
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