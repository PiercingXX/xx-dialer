package com.piercingxx.xxdialer.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.piercingxx.xxdialer.util.E164
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The call-grid controller behind the in-call surface (design §12 In-call,
 * R1 hold/swap). Two halves:
 *
 * PURE — [Cell]/[Line]/[Grid] and [reduceGrid] carry every decision
 * (which call is primary, is a second call waiting, is swap legal); they
 * import nothing from android.* and are JVM-tested.
 *
 * LIVE — [CallGrid] is the process-wide registry of Telecom calls.
 * XxInCallService (telecom/) forwards its lifecycle into [callAdded],
 * [callRemoved], [endpointsChanged], [endpointChanged]; the activity only
 * renders snapshots and issues commands. Jar-35 fact (probed): mute and
 * audio routing exist ONLY on InCallService — Call carries neither
 * getCallEndpoint/setMuted nor any audio method — so the service reference
 * is the only path for both routing implementations (§12: CallEndpoint
 * path on 34+, deprecated CallAudioState route fallback below it).
 */

// ---- pure model (no android.*; JVM-tested) ------------------------------------

/** Coarse per-call line state; mapped from Call.STATE_* at the edge. */
enum class Line { ACTIVE, HELD, WAITING, OUTGOING, ENDED }

data class Cell(val key: String, val label: String, val line: Line)

data class Grid(
    val primary: Cell?,
    val primaryHeld: Boolean,
    val waiting: Cell?,
    val canSwap: Boolean,
) {
    val isEmpty: Boolean get() = primary == null && waiting == null
}

/**
 * First-match reduction (§12): an ACTIVE call is primary; else an outgoing
 * one still connecting; else whatever is left on hold. A WAITING call
 * alongside anything live is the call-waiting card. Swap is legal for
 * exactly one ACTIVE + one HELD pair.
 */
fun reduceGrid(cells: List<Cell>): Grid {
    val live = cells.filterNot { it.line == Line.ENDED }
    val active = live.filter { it.line == Line.ACTIVE }
    val held = live.filter { it.line == Line.HELD }
    val outgoing = live.filter { it.line == Line.OUTGOING }
    val waiting = live.firstOrNull { it.line == Line.WAITING }
    val primary = active.firstOrNull() ?: outgoing.firstOrNull() ?: held.firstOrNull()
    return Grid(
        primary = primary,
        primaryHeld = primary?.line == Line.HELD,
        waiting = waiting,
        canSwap = active.size == 1 && held.size == 1,
    )
}

/**
 * When may the in-call surface dismiss itself?
 *
 * "The grid is empty" is not the answer on its own, and that is the whole
 * reason this exists. The in-call screen is launched OPTIMISTICALLY — the
 * moment placeCall() returns (telecom/CallManager) and the moment answer is
 * tapped (IncomingCallActivity) — both of which are before Telecom has added
 * the Call. A surface that finished on the first empty snapshot would close
 * itself in the first frames of every outgoing call.
 *
 * So the exit is LATCHED: the grid must have held a live call at least once
 * before emptiness means "the call ended" rather than "the call has not
 * arrived yet". [fire] returns true exactly once, because finish() is not
 * idempotent in any way worth relying on and later snapshots keep arriving
 * while the activity tears down.
 *
 * A surface that is opened after the call is already gone (a stale ongoing
 * notification, say) therefore never fires — it renders "No active call" and
 * waits for the user, which is the pre-existing behaviour and the honest one:
 * self-closing a screen the user deliberately opened is worse than a screen
 * that says nothing is happening.
 */
class CallEndExit {

    private var sawLiveCall = false
    private var fired = false

    /** Feed every snapshot; true means "the last call just went away — leave". */
    fun fire(gridEmpty: Boolean): Boolean {
        if (!gridEmpty) {
            sawLiveCall = true
            return false
        }
        if (!sawLiveCall || fired) return false
        fired = true
        return true
    }
}

/** mm:ss under an hour, h:mm:ss past it; negatives clamp to 00:00. */
fun formatDuration(elapsedSeconds: Long): String {
    val s = elapsedSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

// ---- live registry ------------------------------------------------------------

object CallGrid {

    private val calls = CopyOnWriteArrayList<Call>()
    private val activeSince = HashMap<Call, Long>()
    private val answering: MutableSet<Call> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val handler = Handler(Looper.getMainLooper())

    /** Notified on the main thread after every mutation. Per-instance, never a single slot. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /**
     * Set by XxInCallService.onCreate; it forwards onCallAdded/onCallRemoved
     * and the endpoint callbacks here. Null only before the service first
     * binds, when the grid correctly renders empty and commands no-op.
     */
    @Volatile var service: InCallService? = null

    /** Pushed by the service on onAvailableCallEndpointsChanged (API 34+). */
    @Volatile private var endpoints: List<CallEndpoint> = emptyList()

    /** Pushed by the service on onCallEndpointChanged (API 34+). */
    @Volatile private var currentEndpoint: CallEndpoint? = null

    private var swapRequest: SwapRequest? = null

    private val swapFallback = Runnable {
        val req = swapRequest ?: return@Runnable
        swapRequest = null
        calls.firstOrNull { stableKey(it) == req.parkedKey }?.let(::unhold)
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            if (newState == Call.STATE_ACTIVE) recordAnchor(call)
            if (newState == Call.STATE_HOLDING) completeSwapIfHolding(stableKey(call))
            notifyChange()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            notifyChange()
        }
    }

    fun callAdded(call: Call) {
        if (!calls.addIfAbsent(call)) return
        runCatching { call.registerCallback(callCallback) }
        if (call.state == Call.STATE_ACTIVE) recordAnchor(call)
        notifyChange()
    }

    fun callRemoved(call: Call) {
        if (!calls.remove(call)) return
        runCatching { call.unregisterCallback(callCallback) }
        activeSince.remove(call)
        answering.remove(call)
        notifyChange()
    }

    /** True from the moment [answer] is issued until Telecom removes the call. */
    fun isAnswering(call: Call): Boolean = answering.contains(call)

    fun endpointsChanged(list: List<CallEndpoint>) {
        endpoints = list
        notifyChange()
    }

    fun endpointChanged(endpoint: CallEndpoint) {
        currentEndpoint = endpoint
        notifyChange()
    }

    // ---- snapshot -------------------------------------------------------------

    fun snapshot(): Grid = reduceGrid(calls.map(::cellOf))

    fun cellOf(call: Call): Cell =
        Cell(key = stableKey(call), label = displayLabel(call), line = lineOf(call.state))

    fun lineOf(state: Int): Line = when (state) {
        Call.STATE_ACTIVE, Call.STATE_AUDIO_PROCESSING -> Line.ACTIVE
        Call.STATE_HOLDING -> Line.HELD
        Call.STATE_RINGING -> Line.WAITING
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT,
        Call.STATE_PULLING_CALL, Call.STATE_SIMULATED_RINGING,
        -> Line.OUTGOING
        else -> Line.ENDED
    }

    fun anchorMillis(call: Call): Long? = synchronized(activeSince) { activeSince[call] }

    /** Wall-clock instant the call went ACTIVE; drives the duration ticker. */
    private fun recordAnchor(call: Call) {
        synchronized(activeSince) { activeSince.getOrPut(call) { System.currentTimeMillis() } }
    }

    fun callFor(cell: Cell): Call? = calls.firstOrNull { stableKey(it) == cell.key }

    fun waitingCall(): Call? = calls.firstOrNull { lineOf(it.state) == Line.WAITING }

    /**
     * The RINGING call carrying this number — disambiguates when two calls
     * ring at once (the shared INCOMING card can only present one). Null for
     * a withheld number or no match; callers fall back to [waitingCall].
     */
    fun ringingCallFor(number: String?): Call? {
        val want = number?.let { E164.normalize(it) ?: it } ?: return null
        return calls.firstOrNull { call ->
            lineOf(call.state) == Line.WAITING &&
                call.details.handle?.schemeSpecificPart
                    ?.let { raw -> E164.normalize(raw) ?: raw } == want
        }
    }

    /** Hold-and-answer for a SPECIFIC ringing call (§12 call waiting). */
    fun answer(call: Call): Boolean {
        if (lineOf(call.state) != Line.WAITING) return false
        answering.add(call)
        calls.firstOrNull { lineOf(it.state) == Line.ACTIVE }?.let(::hold)
        runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
        return true
    }

    private fun stableKey(call: Call): String {
        // Details.id is public only from API 35; identity hash is stable for
        // the lifetime of the Call object either way.
        val id = if (Build.VERSION.SDK_INT >= 35) call.details.id else ""
        return id.ifEmpty { Integer.toHexString(System.identityHashCode(call)) }
    }

    /** Contact display name → carrier CNAP → number → withheld label (§6). */
    fun displayLabel(call: Call): String {
        val d = call.details
        return d.contactDisplayName
            ?: d.callerDisplayName?.takeIf { it.isNotBlank() }
            ?: d.handle?.schemeSpecificPart
            ?: WITHHELD_LABEL
    }

    // ---- commands -----------------------------------------------------------------

    fun setMuted(muted: Boolean) {
        runCatching { service?.setMuted(muted) }
    }

    fun isMuted(): Boolean = runCatching { service?.callAudioState?.isMuted }.getOrNull() == true

    fun hold(call: Call) { runCatching { call.hold() } }

    fun unhold(call: Call) { runCatching { call.unhold() } }

    fun end(call: Call) { runCatching { call.disconnect() } }

    /** §12 swap: hold the live call, unhold the parked one only once HOLDING lands. */
    fun swap(): Boolean {
        val active = calls.firstOrNull { lineOf(it.state) == Line.ACTIVE } ?: return false
        val held = calls.firstOrNull { lineOf(it.state) == Line.HELD } ?: return false
        swapRequest = SwapRequest(holdingKey = stableKey(active), parkedKey = stableKey(held))
        hold(active)
        handler.removeCallbacks(swapFallback)
        handler.postDelayed(swapFallback, SWAP_UNHOLD_FALLBACK_MS)
        return true
    }

    private fun completeSwapIfHolding(holdingKey: String) {
        val parked = onHoldingForSwap(swapRequest, holdingKey) ?: return
        swapRequest = null
        handler.removeCallbacks(swapFallback)
        calls.firstOrNull { stableKey(it) == parked }?.let(::unhold)
    }

    /**
     * Hold-and-answer (§12 call waiting): park the active call, then answer
     * the waiting one audio-only. Telecom enforces the actual transition.
     */
    fun answerWaiting(): Boolean = waitingCall()?.let(::answer) ?: false

    fun endActive(): Boolean {
        val target = calls.firstOrNull { lineOf(it.state) == Line.ACTIVE }
            ?: calls.firstOrNull { lineOf(it.state) == Line.OUTGOING }
            ?: return false
        end(target)
        return true
    }

    fun dtmfStart(digit: Char): Boolean {
        val target = calls.firstOrNull { lineOf(it.state) == Line.ACTIVE }
            ?: calls.firstOrNull { lineOf(it.state) == Line.OUTGOING }
            ?: return false
        return runCatching { target.playDtmfTone(digit) }.isSuccess
    }

    fun dtmfStop() {
        calls.forEach { runCatching { it.stopDtmfTone() } }
    }

    // ---- audio routing (§12: both paths) ------------------------------------------

    /** One routable destination, whichever implementation produced it. */
    data class Route(val label: String, val endpoint: CallEndpoint?, val legacyRoute: Int) {
        val isLegacy: Boolean get() = endpoint == null
    }

    /**
     * Available destinations. API 34+ prefers real CallEndpoints (BLE Audio
     * and hearing aids included, §12); below that — or when the service has
     * pushed nothing — synthesize from the supported-route mask.
     */
    @Suppress("DEPRECATION") // §12: the legacy CallAudioState path is required, not accidental
    fun routes(): List<Route> {
        if (Build.VERSION.SDK_INT >= 34 && endpoints.isNotEmpty()) {
            return endpoints.map { Route(it.endpointName?.toString() ?: typeLabel(it.endpointType), it, -1) }
        }
        val mask = runCatching { service?.callAudioState?.supportedRouteMask }.getOrNull()
            ?: return emptyList()
        val current = runCatching { service?.callAudioState?.route }.getOrDefault(0)
        return legacyOptions(mask).map { (label, route) ->
            val shown = if (route == current) "$label · current" else label
            Route(shown, null, route)
        }
    }

    fun requestRoute(route: Route) {
        val svc = service ?: return
        if (route.endpoint != null && Build.VERSION.SDK_INT >= 34) {
            runCatching {
                svc.requestCallEndpointChange(
                    route.endpoint,
                    { r -> handler.post(r) },
                ) { /* OutcomeReceiver: void result; failures surface on next push */ }
            }
        } else if (route.legacyRoute != -1) {
            runCatching { svc.setAudioRoute(route.legacyRoute) }
        }
    }

    /** Speaker button lights when audio leaves the earpiece (§12 mono look). */
    @Suppress("DEPRECATION")
    fun isRoutedAwayFromEar(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) currentEndpoint?.let {
            return it.endpointType != CallEndpoint.TYPE_EARPIECE &&
                it.endpointType != CallEndpoint.TYPE_WIRED_HEADSET
        }
        val route = runCatching { service?.callAudioState?.route }.getOrDefault(CallAudioState.ROUTE_EARPIECE)
        return route == CallAudioState.ROUTE_SPEAKER ||
            route == CallAudioState.ROUTE_BLUETOOTH ||
            route == CallAudioState.ROUTE_STREAMING
    }

    private fun legacyOptions(mask: Int): List<Pair<String, Int>> = listOfNotNull(
        CallAudioState.ROUTE_SPEAKER.takeIf { mask and CallAudioState.ROUTE_SPEAKER != 0 }
            ?.let { "Speaker" to it },
        CallAudioState.ROUTE_BLUETOOTH.takeIf { mask and CallAudioState.ROUTE_BLUETOOTH != 0 }
            ?.let { "Bluetooth" to it },
        CallAudioState.ROUTE_WIRED_HEADSET.takeIf { mask and CallAudioState.ROUTE_WIRED_HEADSET != 0 }
            ?.let { "Wired headset" to it },
        CallAudioState.ROUTE_EARPIECE.takeIf { mask and CallAudioState.ROUTE_EARPIECE != 0 }
            ?.let { "Phone" to it },
    )

    private fun typeLabel(type: Int): String = when (type) {
        CallEndpoint.TYPE_BLUETOOTH -> "Bluetooth"
        CallEndpoint.TYPE_SPEAKER -> "Speaker"
        CallEndpoint.TYPE_WIRED_HEADSET -> "Wired headset"
        CallEndpoint.TYPE_STREAMING -> "Streaming"
        CallEndpoint.TYPE_EARPIECE -> "Phone"
        else -> "Audio output"
    }

    private fun notifyChange() {
        handler.post { listeners.forEach { it() } }
    }

    private const val WITHHELD_LABEL = "Unknown caller"
    private const val SWAP_UNHOLD_FALLBACK_MS = 800L
}

/** Hold this key, then unhold [parkedKey] once HOLDING is reported. */
data class SwapRequest(val holdingKey: String, val parkedKey: String)

/** Pure seam: when the call we asked to hold reports HOLDING, unhold the parked one. */
fun onHoldingForSwap(request: SwapRequest?, holdingKey: String): String? {
    if (request == null) return null
    if (request.holdingKey != holdingKey) return null
    return request.parkedKey
}
