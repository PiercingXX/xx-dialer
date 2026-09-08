package com.piercingxx.xxdialer.telecom

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.piercingxx.xxdialer.core.CallerFacts
import com.piercingxx.xxdialer.data.ContactMirror
import com.piercingxx.xxdialer.data.ContactMirrorEntity
import com.piercingxx.xxdialer.data.StealthBlock
import com.piercingxx.xxdialer.data.XxDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/**
 * Assembles [CallerFacts] from the warm mirror, the screen log, CallLog and
 * the emergency marker (design §5/§6, D4). Every query is wrapped: a failure
 * degrades toward the §15 law — failures ring more, never less — but never
 * fabricates the *signals* that pierce silence (see per-query notes).
 *
 * Mirror sourcing (B1): screening runs WARM-ONLY (§15 — "verdicts come from
 * warm data or not at all"); the ring-time pipeline supplies the LIVE
 * PhoneLookup fact (§9, no clock) via [assemble]'s mirrorOverride, which
 * ContactMirror.liveLookup already falls back to the warm mirror.
 */
class FactSource(private val db: XxDatabase, private val context: Context) {

    suspend fun assemble(
        numberE164: String?,
        presentation: Int,
        stirFailed: Boolean,
        emergencyCallbackExtraPresent: Boolean,
        nowEpochMillis: Long,
        elapsedNowMillis: Long,
        now: LocalDateTime,
        mirrorOverride: ContactMirrorEntity? = null,
        includeEnhancements: Boolean = true,
    ): CallerFacts {
        val mirror = mirrorOverride ?: warmMirror(numberE164)
        val groupBlocked = mirror != null && runCatching {
            db.tierMemberDao().keysFor(StealthBlock.GROUP).contains(mirror.lookupKey)
        }.getOrDefault(false)
        return CallerFacts(
            number = numberE164,
            saved = mirror?.saved == true,
            starred = mirror?.starred == true,
            bizTier = mirror?.bizTier == true,
            sendToVoicemail = mirror?.sendToVoicemail == true,
            userBlocked = false, // patterns are evaluated inside decide() via rules; system blocklist never reaches us (§4.4)
            groupBlocked = groupBlocked,
            stirFailed = stirFailed,
            repeatCaller = if (includeEnhancements) repeatCaller(numberE164, nowEpochMillis) else false,
            recentOutgoing = if (includeEnhancements) recentOutgoing(numberE164, nowEpochMillis) else false,
            cnapName = null, // CNAP is context-only; arrives at the UI layer later (§6)
            emergencyWindow = emergencyCallbackExtraPresent ||
                emergencyWindow(nowEpochMillis, elapsedNowMillis),
            withheld = DetailsCodec.isWithheld(presentation),
        )
    }

    /** Stale mirror ⇒ all-false ⇒ caller classifies unknown → rings in-window (§15, D5). */
    private suspend fun warmMirror(numberE164: String?): ContactMirrorEntity? {
        if (numberE164 == null) return null
        return runCatching { db.contactMirrorDao().findByE164(numberE164) }
            .onFailure { Log.w(TAG, "mirror lookup failed", it) }
            .getOrNull()
    }

    /**
     * FAILURE-DIRECTION CHOICE (documented): on query failure this returns
     * FALSE, not true. Repeat-pierce is an enhancement that rings MORE; a
     * fabricated `true` under DB trouble would ring *everything* through
     * every window and defeat the policy wholesale. The hard edge of the §15
     * law is that no failure may BLOCK or silence-by-default — a missed pierce
     * still leaves the call surfaced on the silent channel, answerable, and a
     * genuine repeat caller calls again; Expecting-a-call covers the
     * waiting-on-the-clinic class (§7.1). False-negative accepted.
     */
    private suspend fun repeatCaller(numberE164: String?, nowEpochMillis: Long): Boolean {
        if (numberE164 == null) return false
        return runCatching {
            db.screenLogDao().silenceCountSince(numberE164, nowEpochMillis - REPEAT_WINDOW_MS) > 0
        }
            .onFailure { Log.w(TAG, "repeat-caller query failed", it) }
            .getOrDefault(false)
    }

    /**
     * FAILURE-DIRECTION CHOICE (documented): on failure returns FALSE.
     * Same reasoning as repeat: recent-outgoing only promotes to Ring (row 9);
     * fabricating `true` on a SecurityException (role not yet granted) would
     * ring every unknown caller constantly. The enhancement path fails quiet;
     * nothing here can ever produce a quieter-than-policy BLOCK.
     */
    private suspend fun recentOutgoing(numberE164: String?, nowEpochMillis: Long): Boolean {
        if (numberE164 == null) return false
        return runCatching {
            // M2: the CallLog scan is blocking provider I/O — it must not run
            // on the main-thread ring pipeline. Dispatch to IO and bound it so
            // a slow provider cannot stall the ring; timeout/failure reads
            // FALSE (the documented fail-quiet enhancement choice above).
            withTimeoutOrNull(RECENT_OUTGOING_BUDGET_MS) {
                withContext(Dispatchers.IO) {
                    queryRecentOutgoing(numberE164, nowEpochMillis - RECENT_OUTGOING_MS)
                }
            } ?: false
        }
            .onFailure { Log.w(TAG, "recent-outgoing query failed", it) }
            .getOrDefault(false)
    }

    private fun queryRecentOutgoing(numberE164: String, sinceMillis: Long): Boolean {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), sinceMillis.toString()),
            "${CallLog.Calls.DATE} DESC",
        ) ?: return false
        cursor.use {
            val col = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            while (it.moveToNext()) {
                // Normalized compare via core E164 (§6): one caller, one identity.
                if (com.piercingxx.xxdialer.util.E164.normalize(it.getString(col)) == numberE164) return true
            }
        }
        return false
    }

    /**
     * The platform's EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS is honored by
     * the caller regardless; the marker read failing only loses our own R10
     * belt — the platform's 2 h suppression remains upstream (§4.4).
     */
    private suspend fun emergencyWindow(nowEpochMillis: Long, elapsedNowMillis: Long): Boolean =
        runCatching { db.emergencyMarkerDao().get() }
            .onFailure { Log.w(TAG, "emergency marker read failed", it) }
            .getOrNull()
            ?.let { EmergencyWindow.active(nowEpochMillis, it.lastEmergencyCallAt, elapsedNowMillis, it.lastEmergencyElapsed) }
            ?: false

    private companion object {
        const val TAG = "FactSource"
        const val REPEAT_WINDOW_MS = EnhancementWindows.REPEAT_MS
        const val RECENT_OUTGOING_MS = EnhancementWindows.RECENT_OUTGOING_MS
        const val RECENT_OUTGOING_BUDGET_MS = 750L // M2: bound the cold CallLog scan
    }
}

/** D10/D14 window arithmetic — exclusive at the far edge, JVM-testable. */
internal object EnhancementWindows {
    const val REPEAT_MS = 15L * 60 * 1000
    const val RECENT_OUTGOING_MS = 48L * 60 * 60 * 1000

    fun inRepeatWindow(nowMillis: Long, lastSilenceAtMillis: Long): Boolean {
        val age = nowMillis - lastSilenceAtMillis
        return age in 0 until REPEAT_MS
    }

    fun inRecentOutgoingWindow(nowMillis: Long, lastOutgoingAtMillis: Long): Boolean {
        val age = nowMillis - lastOutgoingAtMillis
        return age in 0 until RECENT_OUTGOING_MS
    }
}
