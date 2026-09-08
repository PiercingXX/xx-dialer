package com.piercingxx.xxdialer.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.util.E164
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
                    HISTORY -> historyCursor(ctx, selectionArgs)
                    else -> null
                }
            }
        }.getOrNull()
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        WINDOW -> "vnd.android.cursor.item/vnd.${TierExport.AUTHORITY}.window"
        BIZ -> "vnd.android.cursor.dir/vnd.${TierExport.AUTHORITY}.biz"
        GROUPS -> "vnd.android.cursor.dir/vnd.${TierExport.AUTHORITY}.groups"
        HISTORY -> "vnd.android.cursor.dir/vnd.${TierExport.AUTHORITY}.history"
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
                if (StealthBlock.isGroup(tier)) BlockedGroupSync.sync(ctx, key, blocked = true)
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
                    runBlocking {
                        ServiceLocator.db(ctx).tierMemberDao().delete(key, tier)
                        if (StealthBlock.isGroup(tier)) {
                            BlockedGroupSync.sync(ctx, key, blocked = false)
                        }
                    }
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

    private suspend fun historyCursor(ctx: Context, selectionArgs: Array<out String>?): Cursor {
        val lookupKey = selectionArgs?.firstOrNull()?.trim().orEmpty()
        val cursor = MatrixCursor(TierExport.HISTORY_COLUMNS)
        if (lookupKey.isEmpty()) return cursor
        val wanted = numbersFor(ctx, lookupKey)
        if (wanted.isEmpty()) return cursor
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NUMBER,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { log ->
            val dateCol = log.getColumnIndex(CallLog.Calls.DATE)
            val typeCol = log.getColumnIndex(CallLog.Calls.TYPE)
            val durCol = log.getColumnIndex(CallLog.Calls.DURATION)
            val numCol = log.getColumnIndex(CallLog.Calls.NUMBER)
            var scanned = 0
            var kept = 0
            while (log.moveToNext() && scanned < HISTORY_SCAN && kept < HISTORY_KEEP) {
                scanned++
                val raw = if (numCol >= 0) log.getString(numCol) else null
                val e164 = raw?.let { E164.normalize(it) } ?: raw?.trim()
                if (e164.isNullOrEmpty() || e164 !in wanted) continue
                cursor.addRow(
                    arrayOf(
                        if (dateCol >= 0) log.getLong(dateCol) else 0L,
                        if (typeCol >= 0) log.getInt(typeCol) else 0,
                        if (durCol >= 0) log.getInt(durCol) else 0,
                        raw,
                    ),
                )
                kept++
            }
        }
        return cursor
    }

    private suspend fun numbersFor(ctx: Context, lookupKey: String): Set<String> {
        val mirrored = runCatching {
            ServiceLocator.db(ctx).contactMirrorDao().all()
                .filter { it.lookupKey == lookupKey }
                .mapNotNull { E164.normalize(it.e164) ?: it.e164.trim().takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())
        val fromContacts = mutableListOf<String>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
                arrayOf(lookupKey),
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (col < 0) return@use
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(col) ?: continue
                    fromContacts += E164.normalize(raw) ?: raw.trim()
                }
            }
        }
        return (mirrored + fromContacts).filter { it.isNotEmpty() }.toSet()
    }

    companion object {
        private const val WINDOW = 1
        private const val BIZ = 2
        private const val GROUPS = 3
        private const val HISTORY = 4
        private const val HISTORY_SCAN = 1000
        private const val HISTORY_KEEP = 40
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TierExport.AUTHORITY, TierExport.PATH_WINDOW, WINDOW)
            addURI(TierExport.AUTHORITY, TierExport.PATH_BIZ, BIZ)
            addURI(TierExport.AUTHORITY, TierExport.PATH_GROUPS, GROUPS)
            addURI(TierExport.AUTHORITY, TierExport.PATH_HISTORY, HISTORY)
        }
    }
}
