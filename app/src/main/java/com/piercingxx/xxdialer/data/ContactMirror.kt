package com.piercingxx.xxdialer.data

import android.content.Context
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.PhoneLookup
import android.util.Log
import com.piercingxx.xxdialer.util.E164
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.room.withTransaction
import kotlinx.coroutines.withContext

/**
 * The warm mirror engine (§9): one bulk read of ContactsContract per refresh,
 * an observer that debounces changes, a foreground sweep that prunes dead
 * Business-tier members, and the no-clock [liveLookup] for ring time.
 *
 * Failure direction (§15): a denied or failed read KEEPS the warm mirror —
 * stale facts can play the wrong tone but can never block (D5). A successful
 * read that finds nobody empties the mirror: under Contact Scopes' empty
 * grant every caller then classifies unknown — noisy, never lossy.
 */
class ContactMirror(private val context: Context, private val db: XxDatabase) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContactsObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Outcome of one provider read; drives refresh decisions (§15). */
    private sealed interface Read {
        data class Success(val rows: List<ContactMirrorEntity>) : Read
        data object Denied : Read
        data object Failed : Read
    }

    /** Sweep needs resolved keys too; anything less than full success prunes nothing. */
    private sealed interface SweepRead {
        data class Success(val rows: List<ContactMirrorEntity>, val resolvedKeys: Set<String>) : SweepRead
        data object Incomplete : SweepRead // denied or provider trouble — never prune blind
    }

    // ---- refresh -----------------------------------------------------------

    /**
     * Bulk re-read of the provider into the mirror. SecurityException (no
     * role / scopes yet) keeps the current mirror and never re-prompts;
     * an empty-but-successful read correctly empties it (§15).
     */
    suspend fun refreshAll() {
        when (val read = withContext(Dispatchers.IO) { readProvider() }) {
            is Read.Success -> runCatching { replaceMirror(read.rows) }
                .onFailure { Log.w(TAG, "mirror write failed", it) } // §15: keep going either way
            Read.Denied -> Log.i(TAG, "contacts not readable yet; keeping warm mirror") // no crash, no re-prompt
            Read.Failed -> Log.w(TAG, "contacts provider failed; keeping warm mirror")
        }
    }

    /** Successful read ⇒ whole-truth swap; empty rows legitimately empty the table. */
    private suspend fun replaceMirror(rows: List<ContactMirrorEntity>) =
        db.withTransaction {
            db.contactMirrorDao().deleteAll()
            if (rows.isNotEmpty()) db.contactMirrorDao().upsertAll(rows)
        }

    /** One bulk query over all phone rows (per-number PhoneLookup is ring-time only). */
    private fun readProvider(): Read {
        val cursor = try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                PROJECTION,
                null,
                null,
                null,
            )
        } catch (e: SecurityException) {
            return Read.Denied
        } catch (e: Throwable) {
            Log.w(TAG, "contact query threw", e)
            return Read.Failed
        } ?: return Read.Failed // null cursor = provider trouble; keep warm data

        return cursor.use { c -> parseAll(c) }
    }

    private fun parseAll(cursor: Cursor): Read = try {
        val now = System.currentTimeMillis()
        val rows = sequence {
            while (cursor.moveToNext()) yield(rowAt(cursor, now))
        }.filterNotNull().let { MirrorRows.dedupe(it) }
        Read.Success(rows)
    } catch (e: SecurityException) {
        Read.Denied
    } catch (e: Throwable) {
        Log.w(TAG, "contact cursor parse failed", e)
        Read.Failed // provider may hand back null-typed columns mid-iteration
    }

    /** Provider null columns tolerated throughout (§15). */
    private fun rowAt(c: Cursor, refreshedAt: Long): ContactMirrorEntity? = MirrorRows.row(
        lookupKey = c.str(ContactsContract.Contacts.LOOKUP_KEY),
        rawNumber = c.str(Phone.NUMBER),
        displayName = c.str(Phone.DISPLAY_NAME),
        starred = c.flag(Phone.STARRED),
        customRingtone = c.str(Phone.CUSTOM_RINGTONE),
        sendToVoicemail = c.flag(Phone.SEND_TO_VOICEMAIL),
        refreshedAt = refreshedAt,
    )

    // ---- observer ----------------------------------------------------------

    /**
     * Debounced (2 s) mirror refresh on any contacts change. Registering both
     * URIs catches aggregate-contact edits and raw data-row edits alike.
     */
    fun registerObserver(context: Context) {
        val resolver = context.applicationContext.contentResolver
        observer?.let { resolver.unregisterContentObserver(it) }
        val fresh = ContactsObserver(this)
        try {
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, fresh)
            resolver.registerContentObserver(Phone.CONTENT_URI, true, fresh)
            observer = fresh
        } catch (e: SecurityException) {
            Log.i(TAG, "observer registration denied; sweep-on-foreground still covers us")
        }
    }

    private class ContactsObserver(private val mirror: ContactMirror) :
        android.database.ContentObserver(Handler(Looper.getMainLooper())) {

        override fun onChange(selfChange: Boolean) {
            mirror.handler.removeCallbacksAndMessages(null)
            mirror.handler.postDelayed({ mirror.scope.launch { mirror.refreshAll() } }, DEBOUNCE_MS)
        }
    }

    // ---- foreground sweep --------------------------------------------------

    /**
     * Refresh + Business-tier pruning (§9): members whose LOOKUP_KEY no longer
     * resolves are deleted, logged via [Log.w], and audited in `setting` key
     * "last_tier_prune" as `<epochMillis>:<count>` — never in screen_log,
     * which is calls only (§11). Pruning is skipped whenever the read was not
     * a success: unreadable must never be mistaken for deleted.
     */
    suspend fun sweepOnForeground() {
        val read = withContext(Dispatchers.IO) { readProviderWithKeys() }
        if (read !is SweepRead.Success) {
            Log.i(TAG, "sweep read incomplete; refreshing via guarded path only")
            refreshAll()
            return
        }
        replaceMirror(read.rows)
        pruneUnresolved(read.resolvedKeys)
    }

    private fun readProviderWithKeys(): SweepRead {
        try {
            val resolver = context.contentResolver
            val keys = mutableSetOf<String>()
            val keyCursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                null, null, null,
            ) ?: return SweepRead.Incomplete
            keyCursor.use { c ->
                val col = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                while (c.moveToNext()) c.getString(col)?.let { keys.add(it) }
            }
            val rowsCursor = resolver.query(Phone.CONTENT_URI, PROJECTION, null, null, null)
                ?: return SweepRead.Incomplete
            return rowsCursor.use { c ->
                when (val parsed = parseAll(c)) {
                    is Read.Success -> SweepRead.Success(parsed.rows, keys)
                    else -> SweepRead.Incomplete
                }
            }
        } catch (e: SecurityException) {
            return SweepRead.Incomplete // no scopes yet: unreadable ≠ deleted (§15)
        } catch (e: Throwable) {
            Log.w(TAG, "sweep read failed", e)
            return SweepRead.Incomplete
        }
    }

    private suspend fun pruneUnresolved(resolvedKeys: Set<String>) {
        val dead = runCatching { db.tierMemberDao().bizKeys().filterNot { it in resolvedKeys } }
            .onFailure { Log.w(TAG, "tier prune read failed", it) }
            .getOrDefault(emptyList())
        if (dead.isEmpty()) return
        dead.forEach { key ->
            Log.w(TAG, "pruning unresolved Business-tier member: $key") // §9: pruning is logged
            runCatching { db.tierMemberDao().delete(key) }.onFailure { Log.w(TAG, "prune delete failed", it) }
        }
        auditPrune(dead.size)
    }

    private suspend fun auditPrune(count: Int) {
        runCatching {
            db.settingDao().put(KEY_LAST_TIER_PRUNE, "${System.currentTimeMillis()}:$count")
        }.onFailure { Log.w(TAG, "tier-prune audit write failed", it) }
    }

    // ---- live path ---------------------------------------------------------

    /**
     * Ring-time single query on PhoneLookup.CONTENT_FILTER_URI — the no-clock
     * path (§9). Any failure falls back to the warm mirror row; both failing
     * yields null ⇒ unknown ⇒ rings in-window (§15).
     */
    suspend fun liveLookup(e164: String): ContactMirrorEntity? =
        liveFromProvider(e164)
            ?.also { it.bizTier = runCatching { db.tierMemberDao().bizKeys().contains(it.lookupKey) }.getOrDefault(false) }
            ?: runCatching { db.contactMirrorDao().findByE164(e164) }
                .onFailure { Log.w(TAG, "mirror fallback lookup failed", it) }
                .getOrNull()

    private fun liveFromProvider(e164: String): ContactMirrorEntity? = try {
        val uri = PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(e164).build()
        context.contentResolver.query(uri, LIVE_PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else buildLiveRow(c, e164)
        }
    } catch (e: Throwable) {
        Log.w(TAG, "live lookup failed; falling back to mirror", e)
        null
    }

    private fun buildLiveRow(c: Cursor, e164: String): ContactMirrorEntity? = MirrorRows.liveRow(
        lookupKey = c.str(ContactsContract.Contacts.LOOKUP_KEY),
        e164 = e164,
        displayName = c.str(Phone.DISPLAY_NAME),
        starred = c.flag(Phone.STARRED),
        customRingtone = c.str(Phone.CUSTOM_RINGTONE),
        sendToVoicemail = c.flag(Phone.SEND_TO_VOICEMAIL),
        refreshedAt = System.currentTimeMillis(),
    )

    companion object {
        private const val TAG = "ContactMirror"
        private const val DEBOUNCE_MS = 2_000L
        const val KEY_LAST_TIER_PRUNE = "last_tier_prune"

        private val PROJECTION = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            Phone.NUMBER,
            Phone.DISPLAY_NAME,
            Phone.STARRED,
            Phone.CUSTOM_RINGTONE,
            Phone.SEND_TO_VOICEMAIL,
        )

        private val LIVE_PROJECTION = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            Phone.DISPLAY_NAME,
            Phone.STARRED,
            Phone.CUSTOM_RINGTONE,
            Phone.SEND_TO_VOICEMAIL,
        )
    }
}

/** Cursor accessors that treat every column as possibly-null/absent (§15). */
internal fun Cursor.str(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }

internal fun Cursor.flag(column: String): Boolean =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) != 0 } ?: false

/**
 * Pure mapping seam between provider tuples and mirror rows — JVM-testable
 * without Android (E164 normalization lives in :core).
 */
internal object MirrorRows {

    /** A provider row → mirror row; null when the number has no E.164 identity. */
    fun row(
        lookupKey: String?,
        rawNumber: String?,
        displayName: String?,
        starred: Boolean,
        customRingtone: String?,
        sendToVoicemail: Boolean,
        refreshedAt: Long,
    ): ContactMirrorEntity? {
        val e164 = E164.normalize(rawNumber) ?: return null // no identity ⇒ unmatchable, skip (§6)
        val key = lookupKey?.takeIf { it.isNotBlank() } ?: return null
        return ContactMirrorEntity(
            lookupKey = key,
            e164 = e164,
            displayName = displayName.orEmpty(),
            starred = starred,
            customRingtone = customRingtone,
            sendToVoicemail = sendToVoicemail,
            refreshedAt = refreshedAt,
        )
    }

    /** Live row: number already normalized by the caller (one caller, one identity). */
    fun liveRow(
        lookupKey: String?,
        e164: String,
        displayName: String?,
        starred: Boolean,
        customRingtone: String?,
        sendToVoicemail: Boolean,
        refreshedAt: Long,
    ): ContactMirrorEntity? {
        val key = lookupKey?.takeIf { it.isNotBlank() } ?: return null
        return ContactMirrorEntity(
            lookupKey = key,
            e164 = e164,
            displayName = displayName.orEmpty(),
            starred = starred,
            customRingtone = customRingtone,
            sendToVoicemail = sendToVoicemail,
            refreshedAt = refreshedAt,
        )
    }

    /** One contact may carry several numbers; each E.164 is its own identity. */
    fun dedupe(rows: Sequence<ContactMirrorEntity>): List<ContactMirrorEntity> =
        rows.distinctBy { it.lookupKey to it.e164 }.toList()
}
