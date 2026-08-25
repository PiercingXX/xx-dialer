package com.piercingxx.xxdialer

import android.app.role.RoleManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piercingxx.xxdialer.ui.DialActivity
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * design.md §16, instrumented bullet "role acquisition" — automates PROBE.md
 * §1 · ROLES steps 1–3: Request ROLE_DIALER → accept, Request ROLE_CALL_SCREENING
 * → accept, Audit roles. This is the V1 [VERIFY]: does GrapheneOS enforce,
 * relax, or modify the Restricted-Settings gate on `ROLE_DIALER` for a
 * sideloaded app (the `request_ok_but_not_held_or_user_cancelled` refusal
 * signature)?
 *
 * WS gate: WS0/V1 (todo.md rule #1) — everything downstream trusts the answer.
 *
 * Plain instrumentation, IntentsTestRule-free: role grants cannot be assumed,
 * the system dialog must be accepted by hand, so each request is fired for real
 * and ground truth is read back from [RoleManager.isRoleHeld] — never from the
 * activity result, because `RESULT_OK` with `isRoleHeld == false` IS the V1
 * signature being probed.
 */
@Ignore("requires caiman — see PROBE.md")
@RunWith(AndroidJUnit4::class)
class RoleAcquisitionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val context get() = instrumentation.targetContext

    private val roleManager: RoleManager
        get() = requireNotNull(
            context.getSystemService(RoleManager::class.java),
        ) { "RoleManager absent — not an Android 10+ build" }

    /** PROBE.md §1 step 1: press Request ROLE_DIALER, accept the system dialog. */
    @Test
    fun dialer_role_request_results_in_held_role_after_manual_grant() {
        val available = roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)
        assertTrue("ROLE_DIALER not available on this build", available)

        val held = requestRoleAndAwaitHeld(RoleManager.ROLE_DIALER)

        // PROBE.md §1 audit line shape:
        //   [audit] role=android.app.role.DIALER held=true available=true holders=<pkg>
        // If this fails WITHOUT the dialog rendering a normal decline, record the
        // restricted-settings path taken (App Info → ⋮ → Allow restricted settings)
        // per PROBE.md §1 and paste into design.md §4.1.
        assertTrue("ROLE_DIALER requested but not held — V1 refusal signature", held)
    }

    /** PROBE.md §1 step 2: press Request ROLE_CALL_SCREENING, accept. */
    @Test
    fun callScreening_role_request_results_in_held_role_after_manual_grant() {
        val available = roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
        assertTrue("ROLE_CALL_SCREENING not available on this build", available)

        val held = requestRoleAndAwaitHeld(RoleManager.ROLE_CALL_SCREENING)

        assertTrue("ROLE_CALL_SCREENING requested but not held", held)
    }

    /**
     * PROBE.md §1 step 3 + teardown cross-check: both roles audited together,
     * exactly the state every later §16 test assumes as its precondition.
     */
    @Test
    fun audit_both_roles_held_simultaneously_matches_probe_prerequisite() {
        assertTrue(roleManager.isRoleHeld(RoleManager.ROLE_DIALER))
        assertTrue(roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING))

        // §4.1 note in PROBE.md: did the grant auto-carry phone/contacts/
        // notification permissions? Assert the notification leg here; the full
        // permission dump stays in the probe log lines ([perms] event=state …).
    }

    // ---- harness ------------------------------------------------------------------

    /** Fires the real role intent from a foreground activity of ours, then polls. */
    private fun requestRoleAndAwaitHeld(role: String): Boolean {
        val intent: Intent = roleManager.createRequestRoleIntent(role)
        ActivityScenario.launch(DialActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.startActivityForResult(intent, REQUEST_CODE_ROLE)
            }
        }
        return awaitTrue(MANUAL_GRANT_TIMEOUT_MS) { roleManager.isRoleHeld(role) }
    }

    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return predicate()
    }

    private companion object {
        const val REQUEST_CODE_ROLE = 41
        const val MANUAL_GRANT_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
