package com.piercingxx.xxdialer.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.piercingxx.xxdialer.ServiceLocator
import kotlinx.coroutines.runBlocking

/**
 * Exports Business-tier LOOKUP_KEYs and the current business window to
 * same-signature family apps (TxxT). Query-only; writes stay in People.
 */
class TierExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        return runCatching {
            runBlocking {
                when (MATCHER.match(uri)) {
                    WINDOW -> windowCursor(ctx)
                    BIZ -> bizCursor(ctx)
                    else -> null
                }
            }
        }.getOrNull()
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        WINDOW -> "vnd.android.cursor.item/vnd.${TierExport.AUTHORITY}.window"
        BIZ -> "vnd.android.cursor.dir/vnd.${TierExport.AUTHORITY}.biz"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private suspend fun windowCursor(ctx: android.content.Context): Cursor {
        val window = runCatching { ServiceLocator.rules(ctx).current().businessWindow }
            .getOrNull()
        val cursor = MatrixCursor(TierExport.WINDOW_COLUMNS)
        cursor.addRow(
            TierExport.windowValues(
                window?.startMinuteOfDay ?: TierExport.DEFAULT_START_MINUTE,
                window?.endMinuteOfDay ?: TierExport.DEFAULT_END_MINUTE,
                window?.daysMask ?: TierExport.DEFAULT_DAYS_MASK,
            ),
        )
        return cursor
    }

    private suspend fun bizCursor(ctx: android.content.Context): Cursor {
        val keys = runCatching { ServiceLocator.db(ctx).tierMemberDao().bizKeys() }
            .getOrDefault(emptyList())
        val cursor = MatrixCursor(TierExport.BIZ_COLUMNS)
        keys.forEach { cursor.addRow(arrayOf(it)) }
        return cursor
    }

    companion object {
        private const val WINDOW = 1
        private const val BIZ = 2
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TierExport.AUTHORITY, TierExport.PATH_WINDOW, WINDOW)
            addURI(TierExport.AUTHORITY, TierExport.PATH_BIZ, BIZ)
        }
    }
}
