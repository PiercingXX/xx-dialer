package com.piercingxx.xxphone

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.telecom.TelecomManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piercingxx.xxphone.ui.CallGrid
import com.piercingxx.xxphone.ui.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * design.md §16, instrumented bullet "call waiting hold/swap — the documented
 * third-party failure area gets a dedicated pass" (§15 table: "Second call
 * while in-call … the documented third-party weak spot on Pixels").
 *
 * WS gate: WS7 daily-driver gate — replace the stock dialer for real.
 *
 * Two halves:
 *  - DEVICE (@Ignore'd): place two calls via TelecomManager.placeCall, let the
 *    role-driven InCallService binding feed the live CallGrid registry, drive
 *    hold-and-answer / swap through CallGrid's commands, and assert state
 *    transitions on grid snapshots reduced by ui/CallGrid's pure model.
 *  - PURE: the same reductions are mirrored 1:1 in
 *    app/src/test/…/ui/CallWaitingHoldSwapModelTest.kt, which RUNS on JVM
 *    (:app:testDebugUnitTest) without a device.
 *
 * Notes: this toolchain's android.jar stubs placeCall as void, so Call handles
 * are reached exactly the way the UI reaches them — CallGrid.callFor(snapshot
 * cell). Legs are told apart by their cell labels (the dialed numbers).
 */
@Ignore("requires caiman — see PROBE.md")
@RunWith(AndroidJUnit4::class)
class CallWaitingHoldSwapTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Full arc: A dials and connects; B is placed mid-call; hold-and-answer (or
     * the platform's own parking) yields an ACTIVE+HELD pair; swap flips which
     * leg is live; disconnecting one side closes the swap window.
     */
    @Test
    fun secondCall_holdAndAnswer_then_swap_flipsPair() {
        val telecom = context.getSystemService(TelecomManager::class.java)

        // Leg A off-hook.
        telecom.placeCall(tel(NUMBER_A), Bundle.EMPTY)
        awaitTrue(CONNECT_TIMEOUT_MS) {
            CallGrid.snapshot().primary?.line == Line.ACTIVE
        }
        val labelA = CallGrid.snapshot().primary!!.label
        assertTrue(labelA.contains(NUMBER_A.takeLast(4)))

        // Leg B placed mid-call: either the platform parks A (HELD appears) or
        // B rings in as waiting — both are the §12 call-waiting shapes.
        telecom.placeCall(tel(NUMBER_B), Bundle.EMPTY)
        awaitTrue(CONNECT_TIMEOUT_MS) {
            val s = CallGrid.snapshot()
            s.canSwap || s.waiting != null
        }
        if (!CallGrid.snapshot().canSwap) {
            // WAITING card → hold-and-answer parks A and answers B (§12).
            assertTrue(CallGrid.answerWaiting())
        }
        awaitTrue(SWAP_TIMEOUT_MS) { CallGrid.snapshot().canSwap }

        val pair = CallGrid.snapshot()
        assertEquals("exactly one ACTIVE primary after hold-and-answer", Line.ACTIVE, pair.primary?.line)
        assertTrue("ACTIVE+HELD must enable swap (§12)", pair.canSwap)
        val liveLabel = pair.primary!!.label

        // Swap: the OTHER leg becomes primary (labels differ per leg), still swappable.
        assertTrue(CallGrid.swap())
        awaitTrue(SWAP_TIMEOUT_MS) {
            val after = CallGrid.snapshot()
            after.canSwap && after.primary?.label != liveLabel
        }
        val swapped = CallGrid.snapshot()
        assertTrue(swapped.canSwap)
        assertFalse("flip happened: different leg is now live", swapped.primary!!.label == liveLabel)

        // Disconnecting either side ends the swap window (mirror of
        // disconnected_call_leaves_the_pair_so_swap_ends).
        CallGrid.callFor(swapped.primary!!)?.let(CallGrid::end)
        awaitTrue(END_TIMEOUT_MS) { !CallGrid.snapshot().canSwap }
        assertFalse(CallGrid.snapshot().canSwap)

        runCatching { CallGrid.endActive() }
    }

    private fun tel(e164: String): Uri = Uri.fromParts("tel", e164, null)

    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return predicate()
    }

    private companion object {
        const val NUMBER_A = "+15550100"
        const val NUMBER_B = "+15550101"
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val SWAP_TIMEOUT_MS = 10_000L
        const val END_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
