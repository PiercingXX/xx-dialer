package com.piercingxx.xxdialer.ring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.data.ScreenLogEntity
import com.piercingxx.xxdialer.ui.RecentsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Post-hoc record of silenced calls (§12): a simple high-priority notification
 * on its own `silenced_v1` channel — importance DEFAULT so it appears without
 * heads-up sound; the live silent ring is `ring_silent_v1`'s job. Carries the
 * reason string ("Silenced · Unknown, outside 09–17") and the three inline
 * recovery actions: Call back · Ring next time · Block.
 *
 * The whole category obeys the three-state policy (immediately / daily
 * summary / never) and bursts batch via [SilenceBatcher] so an out-of-window
 * spam run collapses into one card.
 */
object SilencedNotifier {

    private const val TAG = "SilencedNotifier"

    /** §12: this is the post-hoc record channel — heads-up WITHOUT sound is ring_silent_v1's job. */
    private const val CHANNEL_ID = "silenced_v1"

    private const val POLICY_IMMEDIATE = "immediate"
    private const val POLICY_DAILY = "daily"
    private const val POLICY_NEVER = "never"

    /** Daily-summary stasher key; drained by [flushDailyDigestIfDue]. */
    private const val KEY_DAILY_DIGEST = "daily_silence_digest"
    private const val KEY_DIGEST_LAST_FLUSH = "daily_silence_digest_flushed_at"
    private const val DIGEST_CAP = 200
    private const val DIGEST_PERIOD_MS = 24L * 60 * 60 * 1000

    // In-memory burst state (§12): last-post time + running count.
    private val lock = Any()
    private var burstState = SilenceBatcher.State()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    /**
     * Policy-gated entry point as consumed by XxInCallService.finalize:
     * never ⇒ nothing; daily ⇒ digest stasher; immediate ⇒ post (batched).
     */
    fun postSilenced(entry: ScreenLogEntity) {
        val context = AppContextHolder.appContext ?: return // registry not yet built; skip silently
        scope.launch {
            runCatching { dispatch(context.applicationContext, entry) }
                .onFailure { Log.w(TAG, "silenced notification failed", it) }
        }
    }

    /**
     * Stateless primitive: post [count] silenced calls, newest [newest].
     * Bypasses the three-state policy deliberately — policy gates
     * [postSilenced], direct callers own their decision.
     */
    fun postBatch(count: Int, newest: ScreenLogEntity) {
        val context = AppContextHolder.appContext ?: return
        scope.launch {
            runCatching { post(context, count.coerceAtLeast(1), newest) }
                .onFailure { Log.w(TAG, "batched notification failed", it) }
        }
    }

    // ---- internals -----------------------------------------------------------

    private suspend fun dispatch(context: Context, entry: ScreenLogEntity) {
        when (policy(context)) {
            POLICY_NEVER -> Unit // §12: the card never exists
            POLICY_DAILY -> stashDaily(context, entry)
            else -> {
                val outcome = synchronized(lock) {
                    SilenceBatcher.next(burstState, System.currentTimeMillis()).also { burstState = it.state }
                }
                when (outcome) {
                    is SilenceBatcher.Outcome.Single -> post(context, 1, entry)
                    is SilenceBatcher.Outcome.Batch -> post(context, outcome.count, entry)
                }
            }
        }
    }

    private suspend fun policy(context: Context): String =
        runCatching { ServiceLocator.settings(context).silencedNotifPolicy() }.getOrDefault(POLICY_IMMEDIATE)

    private suspend fun stashDaily(context: Context, entry: ScreenLogEntity) {
        val settings = ServiceLocator.settings(context)
        val current = readDigest(context)
        val appended = (current + DigestEntry(at = entry.at, e164 = entry.e164, reason = entry.reason))
            .takeLast(DIGEST_CAP)
        runCatching { settings.setString(KEY_DAILY_DIGEST, gson.toJson(appended)) }
            .onFailure { Log.w(TAG, "daily digest write failed", it) }
        // First stash starts the 24 h clock so the summary lands a day later.
        if (runCatching { settings.getString(KEY_DIGEST_LAST_FLUSH) }.getOrNull().isNullOrBlank()) {
            runCatching { settings.setString(KEY_DIGEST_LAST_FLUSH, System.currentTimeMillis().toString()) }
        }
        maybeFlushDigest(context)
    }

    /**
     * Daily-summary drain (§12 three-state policy, D3: no alarms — flushed
     * opportunistically on each new silenced call and on every app
     * foreground). One card, "N silenced calls", then the stash clears.
     */
    fun flushDailyDigestIfDue() {
        val context = AppContextHolder.appContext ?: return
        scope.launch {
            runCatching { maybeFlushDigest(context.applicationContext) }
                .onFailure { Log.w(TAG, "digest flush failed", it) }
        }
    }

    private suspend fun maybeFlushDigest(context: Context) {
        val settings = ServiceLocator.settings(context)
        val entries = readDigest(context)
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val lastFlush = runCatching { settings.getString(KEY_DIGEST_LAST_FLUSH) }
            .getOrNull()?.toLongOrNull() ?: 0L
        if (now - lastFlush < DIGEST_PERIOD_MS) return
        val newest = entries.maxBy { it.at }
        post(
            context,
            entries.size,
            ScreenLogEntity(
                id = 0, at = newest.at, e164 = newest.e164,
                verdict = "Silence", reason = newest.reason.orEmpty(),
                stir = "", mode = "digest",
            ),
        )
        runCatching {
            settings.setString(KEY_DAILY_DIGEST, "")
            settings.setString(KEY_DIGEST_LAST_FLUSH, now.toString())
        }.onFailure { Log.w(TAG, "digest clear failed", it) }
    }

    private suspend fun readDigest(context: Context): List<DigestEntry> = runCatching {
        gson.fromJson(
            ServiceLocator.settings(context).getString(KEY_DAILY_DIGEST),
            Array<DigestEntry>::class.java,
        )?.toList()
    }.getOrNull().orEmpty()

    private suspend fun post(context: Context, count: Int, newest: ScreenLogEntity) {
        ensureChannel(context)
        val number = newest.e164
        val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentTitle(if (count > 1) "$count silenced calls" else "Silenced call")
            .setContentText(reasonLine(newest))
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(reasonLine(newest)))
            .setContentIntent(contentIntent(context))
        if (count == 1 && number != null) {
            builder.addAction(action(Kind.CallBack, context, number))
                .addAction(action(Kind.RingNextTime, context, number))
                .addAction(action(Kind.Block, context, number))
        }
        // POST_NOTIFICATIONS may be ungranted; a refused card must not crash (§15).
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(NotifIds.SILENCED, builder.build())
        } catch (se: SecurityException) {
            Log.w(TAG, "post refused", se)
        }
    }

    /** "Silenced · Unknown, outside 09–17" — Reason.uiLabel carries the wording (R7). */
    internal fun reasonLine(entry: ScreenLogEntity): String {
        val label = entry.reason.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { Reason.valueOf(raw.uppercase()) }.getOrNull()?.uiLabel ?: raw
        }
        return label?.let { "Silenced · $it" } ?: "Silenced"
    }

    private fun ensureChannel(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Silenced call records",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        } // create-on-existing is a no-op for immutable fields — user edits respected (§15)
    }

    // ---- recovery actions (§12) ---------------------------------------------

    private enum class Kind { CallBack, RingNextTime, Block }

    private fun action(kind: Kind, context: Context, e164: String): androidx.core.app.NotificationCompat.Action {
        val builder = androidx.core.app.NotificationCompat.Action.Builder(0, kind.title(), pending(kind, context, e164))
        return builder.build()
    }

    private fun Kind.title(): String = when (this) {
        Kind.CallBack -> "Call back"
        Kind.RingNextTime -> "Ring next time"
        Kind.Block -> "Block"
    }

    private fun pending(kind: Kind, context: Context, e164: String): PendingIntent? =
        when (kind) {
            Kind.CallBack -> runCatching {
                // L3: placeCall() everywhere (§4.1) — the tap lands in a
                // trampoline that routes through telecom.CallManager.place,
                // never Intent.ACTION_CALL.
                PendingIntent.getActivity(
                    context, RC_CALL_BACK,
                    Intent(context, CallbackTrampolineActivity::class.java)
                        .putExtra(Intents.EXTRA_CALLBACK_E164, e164),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }.getOrNull()
            Kind.RingNextTime -> runCatching {
                // §12: one tap stars the contact so the next call rings —
                // routed to the NON-EXPORTED sibling receiver (B3), same
                // self-addressed discipline as Block.
                PendingIntent.getBroadcast(
                    context, RC_RING_NEXT_TIME,
                    Intent(context, BlockActionsReceiver::class.java)
                        .setAction(Intents.ACTION_RING_NEXT_TIME)
                        .putExtra(Intents.EXTRA_RING_NEXT_E164, e164)
                        .putExtra(Intents.EXTRA_CANCEL_NOTIF_ID, NotifIds.SILENCED),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }.getOrNull()
            Kind.Block -> runCatching {
                // B3: the BlockedNumberContract write is dialer-privileged, so
                // it targets the NON-EXPORTED sibling receiver only — third
                // party apps can never trigger it.
                PendingIntent.getBroadcast(
                    context, RC_BLOCK,
                    Intent(context, BlockActionsReceiver::class.java)
                        .setAction(Intents.ACTION_BLOCK_NUMBER)
                        .putExtra(Intents.EXTRA_BLOCK_NUMBER, e164)
                        .putExtra(Intents.EXTRA_CANCEL_NOTIF_ID, NotifIds.SILENCED),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }.getOrNull()
        }

    private fun contentIntent(context: Context): PendingIntent? =
        runCatching {
            PendingIntent.getActivity(
                context, RC_CONTENT,
                Intent(context, RecentsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

    /** Minimal stashed shape of one silenced call for the daily summary. */
    private data class DigestEntry(val at: Long, val e164: String?, val reason: String?)

    private const val RC_CONTENT = 201
    private const val RC_CALL_BACK = 202
    private const val RC_RING_NEXT_TIME = 203
    private const val RC_BLOCK = 204
}
