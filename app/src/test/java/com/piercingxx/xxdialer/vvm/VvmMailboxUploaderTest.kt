package com.piercingxx.xxdialer.vvm

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The carrier uploader (todo.md VVM V8, T2). A user action on a voicemail row —
 * deleting it, or playing it (which marks it seen) — must be reflected back to the
 * carrier mailbox over IMAP, and once the carrier accepts the upload the result
 * must be reflected into VoicemailContract. This pins that round-trip with an
 * injected IMAP-command seam: the fake records which message id and action were
 * sent, and the test then reads VoicemailContract to confirm a DELETE removed the
 * row and a MARK_SEEN marked it read — the real provider path, not a stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmMailboxUploaderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InMemoryVoicemailProvider.register()
    }

    @Test
    fun uploadReflectsInVoicemailContract() {
        runBlocking {
            val source = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
            val resolver = context.contentResolver

            // A delete uploads a DELETE to the carrier's identity for the row, and
            // once the carrier accepts it the row is removed from VoicemailContract.
            val deleteRowId = insertRow(resolver, source)
            var deleteCommand: Pair<String, VvmUploadPolicy.UploadAction>? = null
            val uploader = VvmMailboxUploader(context) { messageId, action ->
                deleteCommand = messageId to action
                true
            }
            assertTrue(
                "a delete upload must succeed when the carrier accepts it",
                uploader.upload(deleteRowId, delete = true, markSeen = false),
            )
            assertEquals(
                "the delete must target the row's carrier message id",
                VvmUploadPolicy.carrierMessageId(deleteRowId),
                deleteCommand!!.first,
            )
            assertEquals(
                "deleting a voicemail must upload a DELETE",
                VvmUploadPolicy.UploadAction.DELETE,
                deleteCommand!!.second,
            )
            assertNull(
                "a deleted voicemail must be gone from VoicemailContract",
                queryRow(resolver, source, deleteRowId),
            )

            // A played voicemail uploads a MARK_SEEN, and once accepted the row is
            // marked read in VoicemailContract.
            val seenRowId = insertRow(resolver, source)
            var seenCommand: Pair<String, VvmUploadPolicy.UploadAction>? = null
            val seenUploader = VvmMailboxUploader(context) { messageId, action ->
                seenCommand = messageId to action
                true
            }
            assertTrue(
                "a seen-mark upload must succeed when the carrier accepts it",
                seenUploader.upload(seenRowId, delete = false, markSeen = true),
            )
            assertEquals(
                "the seen-mark must target the row's carrier message id",
                VvmUploadPolicy.carrierMessageId(seenRowId),
                seenCommand!!.first,
            )
            assertEquals(
                "playing a voicemail must upload a MARK_SEEN",
                VvmUploadPolicy.UploadAction.MARK_SEEN,
                seenCommand!!.second,
            )
            assertEquals(
                "a played voicemail must be marked read in VoicemailContract",
                1,
                queryRow(resolver, source, seenRowId)!!.second,
            )
        }
    }

    @Test
    fun carrierRefusalLeavesRowUntouched() {
        runBlocking {
            val source = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
            val resolver = context.contentResolver
            val rowId = insertRow(resolver, source)
            val uploader = VvmMailboxUploader(context) { _, _ -> false }
            assertFalse(
                "a refused delete must not succeed",
                uploader.upload(rowId, delete = true, markSeen = false),
            )
            assertTrue(
                "a refused delete must leave the row in VoicemailContract",
                queryRow(resolver, source, rowId) != null,
            )
        }
    }

    private fun insertRow(resolver: ContentResolver, source: Uri): Long {
        val values = ContentValues().apply {
            put(VoicemailContract.Voicemails.NUMBER, "+15551234567")
            put(VoicemailContract.Voicemails.DATE, 1_700_000_000_000L)
            put(VoicemailContract.Voicemails.DURATION, 42)
            put(VoicemailContract.Voicemails.IS_READ, 0)
            put(VoicemailContract.Voicemails.HAS_CONTENT, 0)
        }
        return ContentUris.parseId(resolver.insert(source, values)!!)
    }

    private fun queryRow(resolver: ContentResolver, source: Uri, rowId: Long): Pair<Int, Int>? {
        val rowUri = ContentUris.withAppendedId(source, rowId)
        resolver.query(rowUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails._ID)) to
                    cursor.getInt(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails.IS_READ))
            }
        }
        return null
    }
}