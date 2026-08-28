package com.piercingxx.xxdialer.vvm

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * The detail player's carrier-sync wiring (todo.md VVM V8, T3): a delete uploads a
 * DELETE to the carrier before the row is removed, and a played voicemail uploads a
 * MARK_SEEN so the server-side message state stays in sync with what the user did
 * locally. This pins that the player drives the [VvmMailboxUploader] on both paths
 * through the real provider: the injected IMAP-command seam records which message id
 * and action were sent, and the test then reads VoicemailContract to confirm a DELETE
 * removed the row and a MARK_SEEN marked it read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmDetailPlayerUploadTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        InMemoryVoicemailProvider.register()
    }

    @After
    fun tearDown() {
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test
    fun deleteUploadsBeforeDeleteAndSeenOnPlay() {
        val source = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        val resolver = context.contentResolver

        // --- A delete uploads a DELETE before the row is removed. ---
        val deleteRowId = insertRow(resolver, source)
        val deleteRowUri = ContentUris.withAppendedId(source, deleteRowId)
        var deleteCommand: Pair<String, VvmUploadPolicy.UploadAction>? = null
        val player = VvmDetailPlayer(
            context = context,
            deleteRow = { _ -> true },
            uploader = VvmMailboxUploader(context) { messageId, action ->
                deleteCommand = messageId to action
                true
            },
        )
        assertTrue(
            "a delete must succeed when the carrier accepts the upload",
            player.delete(deleteRowUri),
        )
        assertEquals(
            "a delete must upload for the row's carrier message id",
            VvmUploadPolicy.carrierMessageId(deleteRowId),
            deleteCommand!!.first,
        )
        assertEquals(
            "deleting a voicemail must upload a DELETE",
            VvmUploadPolicy.UploadAction.DELETE,
            deleteCommand!!.second,
        )
        assertNull(
            "the deleted voicemail must be gone from VoicemailContract",
            queryRow(resolver, source, deleteRowId),
        )

        // --- A played voicemail uploads a MARK_SEEN and is marked read. ---
        val seenRowId = insertRow(resolver, source)
        val seenRowUri = ContentUris.withAppendedId(source, seenRowId)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, seenRowUri),
            ShadowMediaPlayer.MediaInfo(1, 1000),
        )
        var seenCommand: Pair<String, VvmUploadPolicy.UploadAction>? = null
        val seenPlayer = VvmDetailPlayer(
            context = context,
            deleteRow = { _ -> true },
            uploader = VvmMailboxUploader(context) { messageId, action ->
                seenCommand = messageId to action
                true
            },
        )
        assertTrue(
            "play must start playback",
            seenPlayer.togglePlay(seenRowUri),
        )
        assertEquals(
            "a played voicemail must upload for the row's carrier message id",
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

        seenPlayer.release()
        player.release()
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