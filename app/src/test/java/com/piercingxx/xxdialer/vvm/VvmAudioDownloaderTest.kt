package com.piercingxx.xxdialer.vvm

import android.content.ContentValues
import android.content.Context
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmAudioDownloaderTest {

    @Before
    fun setUp() {
        InMemoryVoicemailProvider.register()
        VvmImapHostPolicy.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.delete(
            VoicemailContract.Voicemails.buildSourceUri(context.packageName),
            null,
            null,
        )
    }

    @Test
    fun noCredentialsLeavesHasContentZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertVoicemail(context, sourceData = "17")
        val downloader = VvmAudioDownloader(
            context = context,
            loadCreds = { null },
            fetchAudio = { _, _ -> error("must not fetch without credentials") },
        )
        assertFalse(downloader.fetchAndStore(uri))
        assertEquals(0, hasContent(context, uri))
    }

    @Test
    fun missingHostIsNotInvented() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertVoicemail(context, sourceData = "17")
        val downloader = VvmAudioDownloader(
            context = context,
            loadCreds = { VvmSms(type = "STATUS", fields = mapOf("u" to "alice", "pw" to "x")) },
            fetchAudio = { _, _ -> error("must not fetch without a STATUS host") },
        )
        assertFalse(downloader.fetchAndStore(uri))
        assertEquals(0, hasContent(context, uri))
    }

    @Test
    fun audioBytesFlipHasContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertVoicemail(context, sourceData = "17")
        VvmImapHostPolicy.recordStatusHost("mail.example.com")
        var stored = false
        val downloader = VvmAudioDownloader(
            context = context,
            loadCreds = {
                VvmSms(type = "STATUS", fields = mapOf("srv" to "mail.example.com", "u" to "a", "pw" to "b"))
            },
            fetchAudio = { _, uid ->
                assertEquals("17", uid)
                byteArrayOf(1, 2, 3, 4)
            },
            storeAudio = { target, bytes ->
                stored = target == uri && bytes.contentEquals(byteArrayOf(1, 2, 3, 4))
                context.contentResolver.update(
                    target,
                    ContentValues().apply { put(VoicemailContract.Voicemails.HAS_CONTENT, 1) },
                    null,
                    null,
                ) > 0
            },
            cellularDataRequired = { false },
            onCellularData = { false },
        )
        assertTrue(downloader.fetchAndStore(uri))
        assertTrue(stored)
        assertEquals(1, hasContent(context, uri))
    }

    @Test
    fun canSyncFalseDoesNotFetch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = insertVoicemail(context, sourceData = "17")
        VvmImapHostPolicy.recordStatusHost("mail.example.com")
        val downloader = VvmAudioDownloader(
            context = context,
            loadCreds = {
                VvmSms(type = "STATUS", fields = mapOf("srv" to "mail.example.com"))
            },
            fetchAudio = { _, _ -> error("must not fetch when canSync is false") },
            cellularDataRequired = { true },
            onCellularData = { false },
        )
        assertFalse(downloader.fetchAndStore(uri))
        assertEquals(0, hasContent(context, uri))
    }

    private fun insertVoicemail(context: Context, sourceData: String): android.net.Uri {
        val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        val values = ContentValues().apply {
            put(VoicemailContract.Voicemails.NUMBER, "+15551234567")
            put(VoicemailContract.Voicemails.DATE, 1_700_000_000_000L)
            put(VoicemailContract.Voicemails.HAS_CONTENT, 0)
            put(VoicemailContract.Voicemails.SOURCE_DATA, sourceData)
        }
        return context.contentResolver.insert(sourceUri, values)!!
    }

    private fun hasContent(context: Context, uri: android.net.Uri): Int =
        context.contentResolver.query(
            uri,
            arrayOf(VoicemailContract.Voicemails.HAS_CONTENT),
            null,
            null,
            null,
        )!!.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails.HAS_CONTENT))
        }
}
