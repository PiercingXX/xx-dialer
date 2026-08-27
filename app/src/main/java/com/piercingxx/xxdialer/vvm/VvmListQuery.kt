package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.provider.VoicemailContract
import android.util.Log

/**
 * One voicemail row from the mailbox (todo.md VVM list, T2), ready to render.
 * [number] is the raw caller number stored in VoicemailContract (may be null for
 * a withheld caller), [timestampMillis]/[durationSeconds]/[isRead] describe the
 * message, [transcription] is the carrier's text/plain transcription when it is
 * provided (D9: show, never transcribe ourselves), and [callerName] is the
 * resolved display name from the contact store — null when the caller is unknown.
 */
data class VvmListRow(
    val id: Long,
    val number: String?,
    val timestampMillis: Long,
    val durationSeconds: Int,
    val isRead: Boolean,
    val transcription: String?,
    val callerName: String?,
)

/**
 * Reads the mailbox rows from [VoicemailContract] and resolves each caller's
 * name (todo.md VVM list, T2). The platform provider (D3) is the store — this is
 * the read side of the list screen: one collection query, then one name lookup
 * per distinct caller number.
 *
 * Name resolution goes through [resolveName], an injectable seam whose default
 * is the same live [PhoneLookup.CONTENT_FILTER_URI] query [ContactMirror] uses
 * at ring time (design §12: "one optimized round trip — that is the live path").
 * Injecting the seam keeps the query JVM-testable without a Room mirror; the
 * production path ([VoicemailActivity]) uses the default PhoneLookup resolver.
 *
 * Failure direction (§15): a denied or failed provider read returns an empty
 * list — the caller surfaces the honest [VvmListState] decision rather than a
 * half-rendered list, so a query failure never blocks the screen.
 */
class VvmListQuery(
    private val context: Context,
    private val resolveName: (Context, String) -> String? = { ctx, number -> PhoneLookupName.resolve(ctx, number) },
) {

    /**
     * Returns every voicemail row in the mailbox, newest first (DATE desc), with
     * its caller name resolved. A null cursor (provider trouble / no permission)
     * yields an empty list — never a crash (§15).
     */
    fun readRows(): List<VvmListRow> {
        val resolver = context.contentResolver
        val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        val cursor = try {
            resolver.query(sourceUri, PROJECTION, null, null, SORT_ORDER)
        } catch (e: Exception) {
            Log.w(TAG, "voicemail list query failed", e)
            null
        } ?: return emptyList()
        return cursor.use { c -> parseAll(c) }
    }

    private fun parseAll(cursor: Cursor): List<VvmListRow> {
        val rows = mutableListOf<VvmListRow>()
        while (cursor.moveToNext()) {
            VvmListRows.row(cursor, resolveName, context)?.let(rows::add)
        }
        // Newest first, deterministically — the provider's sortOrder is honored on
        // device but the ordering must not depend on it (the test provider returns
        // insertion order), so the query guarantees it itself.
        return rows.sortedByDescending { it.timestampMillis }
    }

    private companion object {
        const val TAG = "VvmListQuery"
        const val SORT_ORDER = "${VoicemailContract.Voicemails.DATE} DESC"

        val PROJECTION = arrayOf(
            VoicemailContract.Voicemails._ID,
            VoicemailContract.Voicemails.NUMBER,
            VoicemailContract.Voicemails.DATE,
            VoicemailContract.Voicemails.DURATION,
            VoicemailContract.Voicemails.IS_READ,
            VoicemailContract.Voicemails.TRANSCRIPTION,
        )
    }
}

/**
 * The default caller-name resolver: one [PhoneLookup.CONTENT_FILTER_URI] query
 * per number, exactly the live path [ContactMirror] uses at ring time. A null or
 * empty result means the caller is unknown — the list renders the raw number
 * instead of a fabricated name. Any provider failure (e.g. a denied read) is
 * propagated, not silently swallowed, so it surfaces as a real error rather
 * than a fabricated "unknown" caller.
 */
object PhoneLookupName {

    fun resolve(context: Context, number: String): String? {
        val uri = PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(number).build()
        return context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)) else null
        }
    }
}

/**
 * Pure mapping seam between a VoicemailContract cursor row and a [VvmListRow],
 * with the caller name already resolved by the caller — JVM-testable without
 * Android. Provider-null columns are tolerated throughout (§15).
 */
internal object VvmListRows {

    fun row(cursor: Cursor, resolveName: (Context, String) -> String?, context: Context): VvmListRow? {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(VoicemailContract.Voicemails._ID))
        val number = cursor.str(VoicemailContract.Voicemails.NUMBER)
        val callerName = number?.takeIf { it.isNotBlank() }?.let { resolveName(context, it) }
        return VvmListRow(
            id = id,
            number = number,
            timestampMillis = cursor.longOrNull(VoicemailContract.Voicemails.DATE) ?: 0L,
            durationSeconds = cursor.intOrNull(VoicemailContract.Voicemails.DURATION) ?: 0,
            isRead = cursor.flag(VoicemailContract.Voicemails.IS_READ),
            transcription = cursor.str(VoicemailContract.Voicemails.TRANSCRIPTION),
            callerName = callerName,
        )
    }

    private fun Cursor.str(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }

    private fun Cursor.flag(column: String): Boolean =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) != 0 } ?: false

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) }

    private fun Cursor.intOrNull(column: String): Int? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) }
}