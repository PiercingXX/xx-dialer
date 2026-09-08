package com.piercingxx.xxdialer.telecom

import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.core.Mode
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.RingPolicy
import com.piercingxx.xxdialer.core.Verdict
import com.piercingxx.xxdialer.data.ScreenLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/**
 * Pre-ring stage of the pipeline (§5 stage table): may decide **Block** only;
 * silence and tone are ring-time decisions in the InCallService. Warm data
 * only — mirror + rules snapshot, no cold provider queries — because the
 * platform's screening deadline is 5 s (§4.2) and its timeout IS the R9
 * fail-open.
 *
 * Contacts exemption is double-guarded: patterns already exempt saved numbers
 * inside RingPolicy, and the mirror's `saved` bit gates the response here
 * too (D5).
 */
class XxCallScreeningService : CallScreeningService() {

    private data class Outcome(val block: Boolean, val stealth: Boolean, val row: ScreenLogEntity)

    /** Off-main screening: respondToCall is legal until the platform's 5 s deadline. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        // An in-flight screen dies unresponded ⇒ the platform allows at its
        // own deadline — the same R9 fail-open as a timeout.
        scope.cancel()
        super.onDestroy()
    }

    override fun onScreenCall(details: Call.Details) {
        // onScreenCall arrives on the process main thread — the same looper
        // that must post the CallStyle notification moments later — so the
        // DB-touching decision work runs off it and responds asynchronously.
        scope.launch {
            // Any crash or 4.5 s timeout ⇒ null ⇒ ALLOW: the platform would
            // have allowed us at 5 s anyway; failing open early keeps the log
            // write out of the critical path (R9).
            val outcome = runCatching {
                withTimeoutOrNull(SCREEN_BUDGET_MS) { screenAndLog(details) }
            }.onFailure { Log.w(TAG, "screening failed; allowing", it) }.getOrNull()

            val response = if (outcome?.block == true) block(stealth = outcome.stealth) else allow()
            runCatching { respondToCall(details, response) } // once per call, this sole site
                .onFailure { Log.w(TAG, "respond failed; platform deadline allows", it) }
        }
    }

    private suspend fun screenAndLog(details: Call.Details): Outcome {
        val db = ServiceLocator.db(this)
        val settings = ServiceLocator.settings(this)
        val nowEpoch = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val now = LocalDateTime.now()

        val presentation = details.handlePresentation
        val number = if (DetailsCodec.isWithheld(presentation)) null else DetailsCodec.numberE164(details.handle?.schemeSpecificPart)
        val stirFailed = DetailsCodec.stirFailed(details.callerNumberVerificationStatus)
        // Minimal-fact screening (5 s budget): repeat/recent are skipped — they
        // only ever promote Ring, so they cannot change a Block decision.
        // emergencyWindow stays IN: an emergency callback matching a silence or
        // block pattern must not be rejected here (R10).
        val emergencyCallbackExtraPresent =
            details.extras?.containsKey(Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS) == true

        val facts = FactSource(db, this).assemble(
            numberE164 = number,
            presentation = presentation,
            stirFailed = stirFailed,
            emergencyCallbackExtraPresent = emergencyCallbackExtraPresent,
            nowEpochMillis = nowEpoch,
            elapsedNowMillis = nowElapsed,
            now = now,
            // M1: minimal-mode — repeat/recent are enhancement queries (the
            // CallLog scan is cold provider I/O) and only ever promote Ring;
            // this stage may decide Block only, so it runs without them
            // (§15: verdicts come from warm data or not at all).
            includeEnhancements = false,
        )

        val rules = ServiceLocator.rules(this).current()
        val verdict = RingPolicy.decide(now, facts, rules)
        val mirrorSaved = facts.saved
        val voicemail = verdict is Verdict.Silence && verdict.reason == Reason.SEND_TO_VOICEMAIL
        val stealth = facts.groupBlocked
        val wantsBlock = (verdict is Verdict.Block && !mirrorSaved) || stealth
        val observing = settings.enforcementMode() == Mode.OBSERVING
        val reject = stealth || ((wantsBlock || voicemail) && !observing)

        val row = ScreenLogEntity(
            id = 0,
            at = nowEpoch,
            e164 = number,
            presentation = presentation,
            verdict = verdict.token(),
            reason = LogRows.reason(verdict, facts, rules, now)?.name.orEmpty(),
            tier = LogRows.tier(facts),
            stir = DetailsCodec.stirLabel(details.callerNumberVerificationStatus),
            cnapName = DetailsCodec.cnapName(details.callerDisplayName, details.callerDisplayNamePresentation),
            mode = LogRows.modeName(settings.enforcementMode()),
            answered = false,
        )
        // Only Block rows are logged here (R7); allow rows are logged at ring
        // time by the InCallService, which owns the full verdict. The observe
        // gate lives HERE (todo #6): observed blocks are logged but allowed.
        if (wantsBlock || reject) persist(row)
        return Outcome(block = reject, stealth = stealth, row = row)
    }

    private suspend fun persist(row: ScreenLogEntity) {
        runCatching {
            val dao = ServiceLocator.db(this@XxCallScreeningService).screenLogDao()
            dao.insert(row)
            dao.pruneTo1000()
        }.onFailure { Log.w(TAG, "screen-log write failed", it) }
    }

    private fun block(stealth: Boolean = false): CallScreeningService.CallResponse =
        CallScreeningService.CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipNotification(stealth) // Blocked group: no missed banner
            .setSkipCallLog(false)
            .build()

    private fun allow(): CallScreeningService.CallResponse =
        CallScreeningService.CallResponse.Builder().build() // empty = untouched

    private companion object {
        const val TAG = "XxCallScreening"
        const val SCREEN_BUDGET_MS = 4500L // inside the platform's 5000 ms deadline (§4.2)
    }
}
