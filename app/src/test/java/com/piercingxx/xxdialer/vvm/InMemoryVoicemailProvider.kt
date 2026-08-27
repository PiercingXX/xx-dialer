package com.piercingxx.xxdialer.vvm

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Robolectric

/**
 * Registers an in-memory VoicemailContract provider for the completion gate
 * (todo.md VVM audio). Robolectric has no working provider for the app's
 * VoicemailContract authority, so contentResolver.query() returns null for both
 * the collection and row URIs — which makes the audio fetcher/player tests fail
 * (readHasContent() returns null → fetchIfMissingContent()/play() return false).
 * This registers a minimal in-memory provider via Robolectric's
 * buildContentProvider(...).create(...) so the insert/query/delete round-trip
 * actually works under Robolectric, exercising the real production read path
 * instead of short-circuiting it.
 */
object InMemoryVoicemailProvider {

    /**
     * Registers an in-memory [ContentProvider] for the VoicemailContract authority
     * the tests build their source URIs against. The URIs carry the platform
     * authority [VoicemailContract.AUTHORITY] ("com.android.voicemail"), not the
     * application's package name — buildSourceUri(packageName) appends the package
     * as a path segment under that authority — so the provider must be registered
     * under the platform authority or the contentResolver never routes to it and
     * every query returns null. Call from a @Before so every test starts from a
     * provider that answers collection and row URI queries.
     */
    fun register() {
        ApplicationProvider.getApplicationContext<Context>()
        val providerInfo = ProviderInfo().apply { authority = VoicemailContract.AUTHORITY }
        Robolectric.buildContentProvider(VoicemailProvider::class.java).create(providerInfo).get()
    }

    /**
     * A minimal in-memory VoicemailContract provider. Stores rows as ContentValues
     * keyed by an auto-incrementing _ID, answers collection queries with every row
     * and row-URI queries with the single matching row, and clears on delete (the
     * tests clear the table between cases). Only the columns the VVM audio tests
     * read are materialized; a column absent from a row comes back null.
     */
    class VoicemailProvider : ContentProvider() {
        private val rows = mutableListOf<ContentValues>()
        private var nextId = 1L

        override fun onCreate(): Boolean = true

        @Synchronized
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val row = ContentValues(values ?: ContentValues())
            row.put(VoicemailContract.Voicemails._ID, nextId)
            rows.add(row)
            return ContentUris.withAppendedId(uri, nextId++)
        }

        @Synchronized
        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? {
            val rowId = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
            val matched = if (rowId >= 0) {
                rows.filter { it.getAsLong(VoicemailContract.Voicemails._ID) == rowId }
            } else {
                rows.toList()
            }
            val columns = projection ?: DEFAULT_COLUMNS
            val cursor = MatrixCursor(columns)
            matched.forEach { row -> cursor.addRow(columns.map { col -> row.get(col) }) }
            return cursor
        }

        @Synchronized
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
            val cleared = rows.size
            rows.clear()
            return cleared
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int = 0

        override fun getType(uri: Uri): String? = null

        private companion object {
            val DEFAULT_COLUMNS = arrayOf(
                VoicemailContract.Voicemails._ID,
                VoicemailContract.Voicemails.NUMBER,
                VoicemailContract.Voicemails.DATE,
                VoicemailContract.Voicemails.DURATION,
                VoicemailContract.Voicemails.IS_READ,
                VoicemailContract.Voicemails.HAS_CONTENT,
                VoicemailContract.Voicemails.TRANSCRIPTION,
            )
        }
    }
}