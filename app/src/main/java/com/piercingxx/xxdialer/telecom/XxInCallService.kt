package com.piercingxx.xxdialer.telecom

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.util.Log
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.core.Mode
import com.piercingxx.xxdialer.core.Reason
import com.piercingxx.xxdialer.core.RingPolicy
import com.piercingxx.xxdialer.core.Tone
import com.piercingxx.xxdialer.core.Verdict
import com.piercingxx.xxdialer.data.ContactMirror
import com.piercingxx.xxdialer.data.ContactMirrorEntity
import com.piercingxx.xxdialer.data.ScreenLogEntity
import com.piercingxx.xxdialer.ring.NotifIds
import com.piercingxx.xxdialer.ring.RingRouter
import com.piercingxx.xxdialer.ring.SilencedNotifier
import com.piercingxx.xxdialer.ui.CallGrid
import com.piercingxx.xxdialer.ui.InCallActivity
import com.piercingxx.xxdialer.ui.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the ringer (§4.3): every ringing call is decided by RingPolicy, logged
 * immediately, routed to its channel by RingRouter, and surfaced as a
 * CallStyle notification — full-screen when allowed, heads-up otherwise.
 *
 * Observe mode is the LAST gate in the chain (todo rule #6, D13): the verdict
 * path runs identically; only the effective verdict handed to the router
 * changes.
 */
class XxInCallService : InCallService() {

    /** Mutable so ring-pipeline completion UPDATES the tracked entry instead of replacing it (L1). */
    private class Entry(
        var logId: Long,
        var entity: ScreenLogEntity?,
        var verdictName: String,
        var answered: Boolean,
        /**
         * What actually happened after the observe gate (D13): in observe
         * mode the raw verdict says Silence while the phone rang — the
         * silenced-call card must follow THIS, or observe changes more than
         * the gate (todo rule #6).
         */
        var effectiveName: String = verdictName,
    )

    /** What id INCOMING currently presents, and for which call (B2 cancel discipline). */
    private class Presented(
        val call: Call,
        val displayName: String,
        val line: CharSequence,
        val e164: String?,
        val cnap: String?,
        val tier: String?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Keyed by the Call itself — Details has no stable public id in jar-35. */
    private val entries = ConcurrentHashMap<Call, Entry>()
    private val ongoingCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /**
     * EVERY call Telecom has handed us, live-state readable, until it is
     * removed. Deliberately not [ongoingCalls]: that set means "has been
     * off-hook at least once" and is what the foreground notification keys
     * off, while the proximity decision needs the phase of every leg —
     * including one that is only ringing, and including a call that was
     * already ACTIVE when this service bound (which never passes through the
     * off-hook transitions at all).
     */
    private val tracked: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val emergencyArmed: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile private var presentedIncoming: Presented? = null

    /**
     * Screen-off-at-the-ear lives HERE, not in InCallActivity, because the
     * lock's lifetime is the CALL's lifetime and this service is the only
     * thing in the process whose lifetime matches it. An activity-scoped lock
     * (acquire in onResume / release in onPause) blanks correctly right up
     * until the user does something ordinary — presses Home mid-call, takes a
     * notification, gets rotated into a config change — at which point the
     * screen stops blanking while the phone is still at their ear, or the
     * screen stays down because onPause never came. Telecom talks to this
     * service about both halves of the decision (call state AND audio route),
     * and it outlives every surface it launches.
     */
    private val proximity by lazy { ProximityGuard(this) }

    /**
     * Last route Telecom pushed, from EITHER routing API (§12 ships both).
     * UNKNOWN until the first push; [currentRoute] re-reads the live audio
     * state before deciding, so that window is narrower than it looks.
     */
    @Volatile private var pushedRoute: EarRoute = EarRoute.UNKNOWN

    private val channelsEnsured = object : Any() {
        @Volatile var done = false
    }
    private val channelsMutex = Mutex()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            this@XxInCallService.handleStateChange(call, newState)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallGrid.service = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        runCatching { call.registerCallback(callCallback) }
        tracked.add(call)
        refreshProximity()
        CallGrid.callAdded(call)
        when (call.state) {
            Call.STATE_RINGING -> {
                if (call.details.extras?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED) == true) {
                    // §4.3 obligation: a silent-ringing call must not ring at all.
                    val entry = Entry(logId = -1, entity = null, verdictName = VERDICT_NONE, answered = false)
                    entries[call] = entry
                    logPlatformSilenced(call, entry) // R7: even this call explains itself
                    return
                }
                entries[call] = Entry(logId = -1, entity = null, verdictName = "", answered = false)
                scope.launch { runCatching { ringPipeline(call) }.onFailure { Log.w(TAG, "ring pipeline failed", it) } }
            }
            Call.STATE_CONNECTING, Call.STATE_DIALING -> {
                maybeRecordEmergency(call)
                markOngoing(call)
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        runCatching { call.unregisterCallback(callCallback) }
        CallGrid.callRemoved(call)
        val entry = entries.remove(call)
        ongoingCalls.remove(call)
        emergencyArmed.remove(call)
        // Before anything that can throw or suspend: the call is gone, so the
        // display must stop being ours. Ordering matters — a failure in the
        // notification bookkeeping below must not be able to strand the screen.
        tracked.remove(call)
        refreshProximity()
        // B2: cancel the incoming card ONLY when it belongs to THIS call —
        // another live call's ring/silence presentation must survive.
        if (presentedIncoming?.call === call) {
            presentedIncoming = null
            notificationManager().cancel(NotifIds.INCOMING)
        }
        if (entry != null) scope.launch { runCatching { finalize(entry) } }
        if (ongoingCalls.isEmpty()) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * The user pressed volume: the RING must stop (§4.3 obligation), but the
     * call stays answerable — so instead of cancelling the incoming card (B2:
     * that loses the answer surface entirely), it is re-posted on the silent
     * channel. Failure direction: surfaced-and-silent beats invisible.
     */
    override fun onSilenceRinger() {
        super.onSilenceRinger()
        val presented = presentedIncoming ?: return
        val call = presented.call
        if (entries[call] == null || call.state != Call.STATE_RINGING) return
        scope.launch {
            runCatching {
                postIncoming(
                    call,
                    ServiceLocator.channelRegistry(this@XxInCallService)
                        .channelIdFor(PURPOSE_RING_SILENT),
                    presented.displayName,
                    presented.line,
                    presented.e164,
                    presented.cnap,
                    presented.tier,
                )
            }.onFailure { Log.w(TAG, "ringer downgrade failed", it) }
        }
    }

    override fun onDestroy() {
        // FIRST, and unconditionally: whatever killed this service — teardown,
        // an unbind, a crash on the way down — must not leave the display held
        // off with nothing left in the process watching the sensor.
        proximity.releaseNow()
        scope.cancel()
        presentedIncoming = null
        tracked.clear()
        CallGrid.service = null
        super.onDestroy()
    }

    override fun onAvailableCallEndpointsChanged(endpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(endpoints)
        CallGrid.endpointsChanged(endpoints)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        // The callback cannot fire below 34, but the guard is not decoration:
        // getEndpointType is a 34+ CALL SITE in a minSdk-31 module, and the
        // explicit form is the convention §12's routing code already uses.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            pushedRoute = endpointRoute(callEndpoint.endpointType)
            refreshProximity()
        }
        CallGrid.endpointChanged(callEndpoint)
    }

    /**
     * The deprecated route callback, kept for the same reason §12 keeps the
     * deprecated routing path: it is the only one that exists below API 34,
     * and the platform still delivers it above 34. Whichever callback fires
     * last wins — they describe the same route, so they cannot disagree.
     *
     * This is the hook that stops the "screen dies while I'm looking at it on
     * speaker" bug: the route leaving the ear mid-call releases the lock
     * immediately (see [ProximityAction.RELEASE_IMMEDIATE]), it does not wait
     * for the next call-state change.
     */
    @Deprecated("Telecom deprecated it in API 34; still the only route signal below 34.")
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let { pushedRoute = legacyRoute(it.route) }
        refreshProximity()
    }

    private fun handleStateChange(call: Call, newState: Int) {
        if (newState == Call.STATE_ACTIVE) entries[call]?.answered = true
        // M3: arm the emergency marker from ANY outgoing-state transition.
        // Telecom may deliver SELECT_PHONE_ACCOUNT/PULLING_CALL before (or
        // instead of) CONNECTING/DIALING; missing those arms would eat the 24 h
        // R10 window. Calls handed to the platform dialer entirely remain out
        // of our reach by construction. maybeRecordEmergency dedupes per call.
        if (
            newState == Call.STATE_CONNECTING ||
            newState == Call.STATE_DIALING ||
            newState == Call.STATE_SELECT_PHONE_ACCOUNT ||
            newState == Call.STATE_PULLING_CALL ||
            newState == Call.STATE_ACTIVE
        ) {
            maybeRecordEmergency(call)
        }
        if (newState == Call.STATE_ACTIVE || newState == Call.STATE_CONNECTING || newState == Call.STATE_DIALING) {
            markOngoing(call)
        }
        // Every transition, not a chosen few: going off-hook must blank, and
        // going on-hold, disconnecting or being screened must stop blanking.
        refreshProximity()
    }

    // ---- proximity blanking (screen off at the ear) ---------------------------

    /**
     * Re-decides the lock from scratch after anything that can change either
     * input. Recomputing the whole answer beats tracking deltas: there is no
     * sequence of missed edges that can leave the lock disagreeing with the
     * current (state, route) pair, which is the failure mode that ends with a
     * screen that will not come back.
     */
    private fun refreshProximity() {
        val action = ProximityPolicy.decide(tracked.map { phaseOf(it.state) }, currentRoute())
        proximity.apply(action)
    }

    /**
     * `Call.STATE_*` → the proximity phase. AUDIO_PROCESSING lands in the
     * ENDED bucket on purpose: the call is being screened in the background,
     * there is no audio in anyone's ear yet, and the user may well be looking
     * at the screen while it happens.
     */
    private fun phaseOf(state: Int): CallPhase = when (state) {
        Call.STATE_ACTIVE -> CallPhase.ACTIVE
        Call.STATE_HOLDING -> CallPhase.HELD
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallPhase.RINGING
        Call.STATE_DIALING, Call.STATE_CONNECTING,
        Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_PULLING_CALL,
        -> CallPhase.OUTGOING
        else -> CallPhase.ENDED
    }

    /**
     * The pushed route when there is one, else a live read of the deprecated
     * audio state — which Telecom always has for a bound InCallService, so
     * UNKNOWN survives only when the platform genuinely will not say.
     */
    @Suppress("DEPRECATION") // same §12 reason: the legacy route API is the floor, not an accident
    private fun currentRoute(): EarRoute {
        if (pushedRoute != EarRoute.UNKNOWN) return pushedRoute
        val live = runCatching { callAudioState?.route }.getOrNull() ?: return EarRoute.UNKNOWN
        return legacyRoute(live)
    }

    @Suppress("DEPRECATION")
    private fun legacyRoute(route: Int): EarRoute = when (route) {
        CallAudioState.ROUTE_EARPIECE -> EarRoute.EARPIECE
        CallAudioState.ROUTE_SPEAKER -> EarRoute.SPEAKER
        CallAudioState.ROUTE_WIRED_HEADSET -> EarRoute.WIRED_HEADSET
        CallAudioState.ROUTE_BLUETOOTH -> EarRoute.BLUETOOTH
        CallAudioState.ROUTE_STREAMING -> EarRoute.STREAMING
        else -> EarRoute.UNKNOWN
    }

    /** The 34+ endpoint types; the version guard lives at the call site above. */
    private fun endpointRoute(type: Int): EarRoute = when (type) {
        CallEndpoint.TYPE_EARPIECE -> EarRoute.EARPIECE
        CallEndpoint.TYPE_SPEAKER -> EarRoute.SPEAKER
        CallEndpoint.TYPE_WIRED_HEADSET -> EarRoute.WIRED_HEADSET
        CallEndpoint.TYPE_BLUETOOTH -> EarRoute.BLUETOOTH
        CallEndpoint.TYPE_STREAMING -> EarRoute.STREAMING
        else -> EarRoute.UNKNOWN
    }

    // ---- ringing pipeline -------------------------------------------------

    private suspend fun ringPipeline(call: Call) {
        val db = ServiceLocator.db(this)
        val details = call.details
        val nowEpoch = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val now = LocalDateTime.now()

        ensureChannels()

        val presentation = details.handlePresentation
        val number = if (DetailsCodec.isWithheld(presentation)) null else DetailsCodec.numberE164(details.handle?.schemeSpecificPart)
        val stirStatus = details.callerNumberVerificationStatus
        val cnap = DetailsCodec.cnapName(details.callerDisplayName, details.callerDisplayNamePresentation)

        // B1/M2: ring-time facts come from the LIVE PhoneLookup path (§9, no
        // clock), fetched once here — off-main-thread and bounded — with the
        // warm mirror as ContactMirror.liveLookup's built-in fallback. The
        // screener never sees this path (§15: warm data only).
        val mirror = lookupMirror(db, number)

        val facts = FactSource(db, this).assemble(
            numberE164 = number,
            presentation = presentation,
            stirFailed = DetailsCodec.stirFailed(stirStatus),
            emergencyCallbackExtraPresent =
                details.extras?.containsKey(Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS) == true,
            nowEpochMillis = nowEpoch,
            elapsedNowMillis = nowElapsed,
            now = now,
            mirrorOverride = mirror,
            includeEnhancements = true,
        )
        val rules = ServiceLocator.rules(this).current()
        val verdict = RingPolicy.decide(now, facts, rules)
        val mode = ServiceLocator.settings(this).enforcementMode()

        // Observed-block dedup (§11 tallies): in observe mode the screener
        // already logged this call's would-block row seconds ago and allowed
        // it through to us; inserting a twin here would double-count the call.
        val screenerRow = if (number != null && mode == Mode.OBSERVING) {
            runCatching { db.screenLogDao().latestFor(number) }.getOrNull()
                ?.takeIf {
                    it.verdict == VERDICT_BLOCK &&
                        it.mode == LogRows.modeName(Mode.OBSERVING) &&
                        nowEpoch - it.at <= SCREENER_CORRELATION_MS
                }
        } else {
            null
        }

        // Write the log NOW: the verdict must survive a crash mid-ring (R7).
        val reason = LogRows.reason(verdict, facts, rules, now)
        val row = screenerRow ?: ScreenLogEntity(
            id = 0,
            at = nowEpoch,
            e164 = number,
            presentation = presentation,
            verdict = verdict::class.simpleName.orEmpty(),
            reason = reason?.name.orEmpty(),
            tier = LogRows.tier(facts),
            stir = DetailsCodec.stirLabel(stirStatus),
            cnapName = cnap,
            mode = LogRows.modeName(mode),
            answered = false,
        )
        val logId = screenerRow?.id ?: runCatching { db.screenLogDao().insert(row) }
            .onFailure { Log.w(TAG, "screen-log write failed", it) }
            .getOrDefault(-1L)

        // GATE, last in the chain: observe mode changes only this line (todo #6).
        val effective = if (mode == Mode.OBSERVING) Verdict.Ring(Tone.DEFAULT) else verdict

        reconcileWarnings()

        // L1: record the verdict against the EXISTING entry (mutate, never
        // replace — an answer landing mid-pipeline must survive) BEFORE the
        // notification posts, so a concurrent finalize can never mistake this
        // presentation for cancellable. A vanished entry means the call died
        // mid-pipeline: reap the late completion below instead of leaking a
        // dead entry into the map.
        val entry = entries[call]
        if (entry != null) {
            entry.logId = logId
            entry.entity = row
            entry.verdictName = verdict::class.simpleName.orEmpty()
            entry.effectiveName = effective::class.simpleName.orEmpty()
            if (call.state == Call.STATE_ACTIVE) entry.answered = true
        }

        val displayName = mirror?.displayName ?: cnap ?: number ?: WITHHELD_LABEL
        val line = contextLine(LogRows.tier(facts), reason, cnap)
        val registry = ServiceLocator.channelRegistry(this)

        when (val choice = RingRouter(registry).route(effective, facts, mirror)) {
            is RingRouter.Choice.Ring ->
                postIncoming(call, choice.channelId, displayName, line, number, cnap, LogRows.tier(facts))
            RingRouter.Choice.Silent ->
                postIncoming(
                    call,
                    registry.channelIdFor(PURPOSE_RING_SILENT),
                    displayName, line, number, cnap, LogRows.tier(facts),
                ) // Silence is still surfaced and answerable (§6, R7)
            RingRouter.Choice.None -> {
                // §5 stage table: a Block that only surfaces here (screener
                // failed open or ran minimal-fact) must still DISPOSE of the
                // call — this app owns the ringer, so an unpresented call
                // would otherwise sit silent and undead until the caller
                // gives up. reject() on a RINGING call declines it.
                if (effective is Verdict.Block) {
                    runCatching { call.reject(false, null) }
                        .onFailure { Log.w(TAG, "ring-time block reject failed", it) }
                    Log.i(TAG, "rejected at ring time: $reason")
                } else {
                    Log.i(TAG, "no presentation for verdict=$effective (Block here is defensive)")
                }
            }
        }

        if (entry == null) {
            scope.launch {
                runCatching {
                    finalize(
                        Entry(
                            logId, row,
                            verdictName = verdict::class.simpleName.orEmpty(),
                            answered = call.state == Call.STATE_ACTIVE,
                            effectiveName = effective::class.simpleName.orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private fun postIncoming(
        call: Call,
        channelId: String,
        displayName: String,
        contextLine: CharSequence,
        e164: String?,
        cnap: String?,
        tier: String?,
    ) {
        val person = Person.Builder().setName(displayName).setImportant(true).build()
        val show = showIntent(displayName, e164, contextLine, cnap, tier)
        val builder = Notification.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(show)
            .setContentTitle(displayName)
            .setContentText(contextLine)
            .setStyle(Notification.CallStyle.forIncomingCall(person, declineIntent(), answerIntent()))
        // canUseFullScreenIntent exists only from API 34; before that the
        // manifest USE_FULL_SCREEN_INTENT grant is unconditional.
        val fsiAllowed = android.os.Build.VERSION.SDK_INT < 34 ||
            notificationManager().canUseFullScreenIntent()
        if (fsiAllowed) {
            builder.setFullScreenIntent(show, true)
        } // else: high-importance channel gives the heads-up; Setup offers the grant (§15)
        notificationManager().notify(NotifIds.INCOMING, builder.build())
        // Recorded in the same main-thread stretch as notify(): finalize's
        // cancel discipline reads this to know whose card id INCOMING shows.
        presentedIncoming = Presented(call, displayName, contextLine, e164, cnap, tier)
    }

    private suspend fun finalize(entry: Entry) {
        val dao = ServiceLocator.db(this).screenLogDao()
        if (entry.logId >= 0) {
            runCatching { dao.updateAnswered(entry.logId, entry.answered) }
                .onFailure { Log.w(TAG, "answered update failed", it) }
            // Gate on what ACTUALLY happened (D13): in observe mode the raw
            // verdict says Silence but the phone rang — no card.
            if (!entry.answered && entry.effectiveName == VERDICT_SILENCE && entry.entity != null) {
                runCatching { SilencedNotifier.postSilenced(entry.entity!!) }
                    .onFailure { Log.w(TAG, "silenced notification failed", it) }
            }
            runCatching { dao.pruneTo1000() }.onFailure { Log.w(TAG, "prune failed", it) }
        }
        clearIncomingIfUnpresented()
    }

    /**
     * B2 cancel discipline: the shared id INCOMING is cancelled only when no
     * other live call's presentation is riding on it. A blanket cancel here
     * could evict a LATER call's answerable ring/silence card (the removal
     * cleanup racing the next ring pipeline). All of this runs without
     * suspension on the serialized main dispatcher, so decision + cancel are
     * atomic with respect to postIncoming's notify + record.
     */
    private fun clearIncomingIfUnpresented() {
        if (entries.isNotEmpty()) return // another live call may be presenting (or about to)
        presentedIncoming = null
        notificationManager().cancel(NotifIds.INCOMING)
    }

    // ---- ongoing (ACTIVE/DIALING) ------------------------------------------

    private fun markOngoing(call: Call) {
        ongoingCalls.add(call)
        scope.launch { runCatching { postOngoing() } }
    }

    private suspend fun postOngoing() {
        val registry = ServiceLocator.channelRegistry(this)
        val notif = Notification.Builder(this, registry.channelIdFor(PURPOSE_ONGOING))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(inCallIntent())
            .setContentTitle(getString(R.string.app_name))
            .build()
        try {
            startForeground(NotifIds.ONGOING, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (t: Throwable) {
            try {
                startForeground(NotifIds.ONGOING, notif) // fall back to manifest-declared types
            } catch (t2: Throwable) {
                Log.w(TAG, "foreground failed; posting plain notification", t2)
                notificationManager().notify(NotifIds.ONGOING, notif)
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private suspend fun ensureChannels() {
        if (channelsEnsured.done) return
        channelsMutex.withLock {
            if (!channelsEnsured.done) {
                runCatching { ServiceLocator.channelRegistry(this@XxInCallService).ensureAll() }
                    .onFailure { Log.w(TAG, "channel creation failed", it) }
                channelsEnsured.done = true
            }
        }
    }

    private suspend fun reconcileWarnings() {
        runCatching { ServiceLocator.channelRegistry(this).reconcileAtCall() }
            .onSuccess { warnings -> warnings.forEach { Log.w(TAG, "channel warning: $it") } } // surfaced on Setup (§15)
            .onFailure { Log.w(TAG, "channel reconcile failed", it) }
    }

    /**
     * B1/M2: the ring-time mirror read is the LIVE PhoneLookup path (§9) via
     * [ContactMirror.liveLookup], which falls back to the warm mirror itself.
     * Dispatched off-main and bounded so a slow/hung provider can never stall
     * the ring — timeout degrades to null ⇒ unknown ⇒ rings in-window (§15).
     */
    private suspend fun lookupMirror(db: com.piercingxx.xxdialer.data.XxDatabase, number: String?): ContactMirrorEntity? {
        if (number == null) return null
        return runCatching {
            withTimeoutOrNull(LIVE_LOOKUP_BUDGET_MS) {
                withContext(Dispatchers.IO) { ServiceLocator.contactMirror(this@XxInCallService).liveLookup(number) }
            }
        }.onFailure { Log.w(TAG, "mirror re-read failed", it) }
            .getOrNull()
    }

    private fun maybeRecordEmergency(call: Call) {
        if (!emergencyArmed.add(call)) return // one record per call, whichever transition arrives first
        val number = call.details.handle?.schemeSpecificPart
        if (!EmergencyMarker.isEmergency(number)) return
        scope.launch {
            runCatching {
                ServiceLocator.emergencyMarker(this@XxInCallService)
                    .record(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }.onFailure { Log.w(TAG, "emergency marker failed", it) }
        }
    }

    /**
     * R7 for the platform-silenced path (§4.3): the call never rings by
     * platform request, but it still gets a screen_log row saying so —
     * async, and the entry is updated in place when the insert lands so
     * finalize's answered-update targets the right row.
     */
    private fun logPlatformSilenced(call: Call, entry: Entry) {
        val details = call.details
        val presentation = details.handlePresentation
        val number = if (DetailsCodec.isWithheld(presentation)) null else DetailsCodec.numberE164(details.handle?.schemeSpecificPart)
        val row = ScreenLogEntity(
            id = 0,
            at = System.currentTimeMillis(),
            e164 = number,
            presentation = presentation,
            verdict = VERDICT_SILENCE,
            reason = REASON_PLATFORM_SILENCED,
            tier = null,
            stir = DetailsCodec.stirLabel(details.callerNumberVerificationStatus),
            cnapName = DetailsCodec.cnapName(details.callerDisplayName, details.callerDisplayNamePresentation),
            mode = "platform",
            answered = false,
        )
        scope.launch {
            runCatching {
                val id = ServiceLocator.db(this@XxInCallService).screenLogDao().insert(row)
                entry.logId = id
                entry.entity = row
            }.onFailure { Log.w(TAG, "platform-silenced log failed", it) }
        }
    }

    private fun contextLine(tier: String?, reason: Reason?, cnap: String?): CharSequence {
        val bits = mutableListOf<String>()
        if (tier != null) bits += tier
        if (reason != null) bits += reason.uiLabel
        if (cnap != null) bits += "carrier says: $cnap" // §6: the only identity signal an unknown gets
        return bits.joinToString(" · ").ifEmpty { getString(R.string.app_name) }
    }

    private fun callExtras(displayName: String, e164: String?, contextLine: CharSequence, cnap: String?, tier: String?): Bundle = Bundle().apply {
        putString(EXTRA_DISPLAY_NAME, displayName)
        putString(EXTRA_NUMBER_E164, e164)
        putCharSequence(EXTRA_CONTEXT_LINE, contextLine)
        putString(EXTRA_CNAP, cnap)
        putString(EXTRA_TIER, tier)
    }

    private fun showIntent(displayName: String, e164: String?, contextLine: CharSequence, cnap: String?, tier: String?): PendingIntent =
        PendingIntent.getActivity(
            this, RC_SHOW,
            incomingIntent(ACTION_SHOW_INCOMING, displayName, e164, contextLine, cnap, tier),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun inCallIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, RC_IN_CALL,
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun answerIntent(): PendingIntent =
        pendingAction(ACTION_ANSWER, RC_ANSWER)

    private fun declineIntent(): PendingIntent =
        pendingAction(ACTION_DECLINE, RC_DECLINE)

    private fun pendingAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this, requestCode,
            Intent(this, IncomingCallActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun incomingIntent(action: String, displayName: String, e164: String?, contextLine: CharSequence, cnap: String?, tier: String?): Intent =
        Intent(this, IncomingCallActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtras(callExtras(displayName, e164, contextLine, cnap, tier))

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val TAG = "XxInCallService"
        const val PURPOSE_RING_SILENT = "ring_silent"
        const val PURPOSE_ONGOING = "ongoing"
        const val VERDICT_SILENCE = "Silence"
        const val VERDICT_BLOCK = "Block"
        const val VERDICT_NONE = "None"
        /** Raw reason token for §4.3 platform-requested silence; UI falls back to the raw string. */
        const val REASON_PLATFORM_SILENCED = "PLATFORM_SILENCED"
        /** Observed screener Block row within this window is the SAME call, not a twin. */
        const val SCREENER_CORRELATION_MS = 10_000L
        const val WITHHELD_LABEL = "Unknown caller"
        const val LIVE_LOOKUP_BUDGET_MS = 1500L // M2: bound the live PhoneLookup hop

        const val ACTION_SHOW_INCOMING = "com.piercingxx.xxdialer.action.SHOW_INCOMING"
        const val ACTION_ANSWER = "com.piercingxx.xxdialer.action.ANSWER_CALL"
        const val ACTION_DECLINE = "com.piercingxx.xxdialer.action.DECLINE_CALL"
        const val EXTRA_DISPLAY_NAME = "com.piercingxx.xxdialer.extra.DISPLAY_NAME"
        const val EXTRA_NUMBER_E164 = "com.piercingxx.xxdialer.extra.NUMBER_E164"
        const val EXTRA_CONTEXT_LINE = "com.piercingxx.xxdialer.extra.CONTEXT_LINE"
        const val EXTRA_CNAP = "com.piercingxx.xxdialer.extra.CNAP"
        const val EXTRA_TIER = "com.piercingxx.xxdialer.extra.TIER"
        const val RC_SHOW = 101
        const val RC_ANSWER = 102
        const val RC_DECLINE = 103
        const val RC_IN_CALL = 104
    }
}
