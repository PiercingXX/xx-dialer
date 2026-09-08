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
 * Family Business + custom groups. Window is query-only. `/biz` and `/groups`
 * accept insert/delete by LOOKUP_KEY. Custom groups live in the same Room
 * table with a composite (lookupKey, tier) key.
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
                    GROUPS -> groupsCursor(ctx)
                    else -> null
                }
            }
        }.getOrNull()
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        WINDOW -> "vnd.android.cursor.item/vnd.${TierExport.AUTHORITY}.window"
        BIZ -> "vnd.android.cursor.dir/vnd.${TierExport.AUTHORITY}.biz"
        GROUPS -> "vnd.android.cursor.dir/vnd.${TierExport.AUTHORITY}.groups"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val key = values?.getAsString(TierExport.COL_LOOKUP_KEY)?.trim().orEmpty()
        if (key.isEmpty()) return null
        val ctx = context ?: return null
        val match = MATCHER.match(uri)
        val tier = when (match) {
            BIZ -> BackupJson.TIER_BIZ
            GROUPS -> TierExport.groupTier(values?.getAsString(TierExport.COL_GROUP_NAME).orEmpty())
            else -> null
        } ?: return null
        return runCatching {
            runBlocking {
                ServiceLocator.db(ctx).tierMemberDao()
                    .upsert(TierMemberEntity(key, tier, System.currentTimeMillis()))
            }
            ctx.contentResolver.notifyChange(uri, null)
            Uri.withAppendedPath(uri, Uri.encode(key))
        }.getOrNull()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val ctx = context ?: return 0
        return when (MATCHER.match(uri)) {
            BIZ -> {
                val key = selectionArgs?.firstOrNull()?.trim().orEmpty()
                if (key.isEmpty()) return 0
                runCatching {
                    runBlocking { ServiceLocator.db(ctx).tierMemberDao().delete(key, BackupJson.TIER_BIZ) }
                    ctx.contentResolver.notifyChange(uri, null)
                    1
                }.getOrDefault(0)
            }
            GROUPS -> {
                val name = selectionArgs?.getOrNull(0)?.trim().orEmpty()
                val key = selectionArgs?.getOrNull(1)?.trim().orEmpty()
                val tier = TierExport.groupTier(name) ?: return 0
                if (key.isEmpty()) return 0
                runCatching {
                    runBlocking { ServiceLocator.db(ctx).tierMemberDao().delete(key, tier) }
                    ctx.contentResolver.notifyChange(uri, null)
                    1
                }.getOrDefault(0)
            }
            else -> 0
        }
    }

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

    private suspend fun groupsCursor(ctx: android.content.Context): Cursor {
        val rows = runCatching { ServiceLocator.db(ctx).tierMemberDao().customGroups() }
            .getOrDefault(emptyList())
        val cursor = MatrixCursor(TierExport.GROUP_COLUMNS)
        rows.forEach { cursor.addRow(arrayOf(it.tier, it.lookupKey)) }
        return cursor
    }

    companion object {
        private const val WINDOW = 1
        private const val BIZ = 2
        private const val GROUPS = 3
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TierExport.AUTHORITY, TierExport.PATH_WINDOW, WINDOW)
            addURI(TierExport.AUTHORITY, TierExport.PATH_BIZ, BIZ)
            addURI(TierExport.AUTHORITY, TierExport.PATH_GROUPS, GROUPS)
        }
    }
}
