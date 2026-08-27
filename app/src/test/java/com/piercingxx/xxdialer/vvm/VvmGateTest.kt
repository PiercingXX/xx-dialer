package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure opt-in gate (todo.md D1/D4, V3). VVM runs only while the toggle is
 * on; when it is off every service callback must no-op and finish its task
 * immediately — Telephony may still bind the default dialer, but we must never
 * ACTIVATE or open a socket. T2 locks the toggle clause; T3 adds the
 * carrier-config validity clause.
 */
class VvmGateTest {

    @Test
    fun shouldRunVvmFalseWhenToggleOff() {
        assertFalse(
            VvmGate.shouldRunVvm(toggleOn = false),
            "toggle off must gate VVM off: no ACTIVATE, no sockets, every callback no-ops",
        )
    }

    @Test
    fun `shouldRunVvm true when toggle on`() {
        assertTrue(
            VvmGate.shouldRunVvm(toggleOn = true),
            "toggle on is the only state that lets VVM run",
        )
    }
}