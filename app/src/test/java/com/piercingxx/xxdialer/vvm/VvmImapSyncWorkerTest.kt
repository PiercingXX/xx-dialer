package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.os.Looper
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The IMAP sync worker (todo.md T3 / V5) must run the mailbox fetch OFF the main
 * thread under a wake lock and write the fetched messages as rows into
 * VoicemailContract (D3). This test pins both invariants with an injected fetch
 * seam: the fake records the thread it ran on (asserted to be non-main) and
 * returns a message whose row must then appear in VoicemailContract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmImapSyncWorkerTest {

    @Test
    fun imapRunsOffMainThreadIntoVoicemailContract() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()

            var fetchThreadName: String? = null
            val fetched = listOf(
                VvmMailboxMessage(
                    number = "+15551234567",
                    timestampMillis = 1_700_000_000_000L,
                    durationSeconds = 42,
                    isRead = false,
                    transcription = "Call me back",
                ),
            )

            val worker = VvmImapSyncWorker(context) { _ ->
                fetchThreadName = Thread.currentThread().name
                fetched
            }

            worker.sync(VvmSms(type = "STATUS", fields = mapOf("srv" to "mail.example.com")))

            // The fetch must run off the main thread — the worker owns the dispatch.
            assertNotEquals(
                "IMAP fetch must not run on the main thread",
                Looper.getMainLooper().thread.name,
                fetchThreadName,
            )

            // The fetched message must be written as a VoicemailContract row.
            val resolver = context.contentResolver
            val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
            resolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                assertTrue("expected at least one voicemail row", cursor.moveToFirst())
                assertEquals(
                    "+15551234567",
                    cursor.getString(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails.NUMBER)),
                )
                assertEquals(
                    42,
                    cursor.getInt(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails.DURATION)),
                )
            }
        }
    }
}