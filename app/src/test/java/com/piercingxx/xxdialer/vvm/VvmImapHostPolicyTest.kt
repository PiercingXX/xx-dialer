package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure IMAP host-constraint seam (todo.md D5, T3). The mailbox may be connected
 * to only over the host named by the last STATUS notification; any other host —
 * a guess, a stale earlier mailbox, or a host from a non-STATUS message — must
 * be refused.
 */
class VvmImapHostPolicyTest {

    @Test
    fun allowedHostEqualsStatusHost() {
        val statusHost = "mail.example.com"

        VvmImapHostPolicy.recordStatusHost(statusHost)

        assertTrue(
            VvmImapHostPolicy.canConnectTo(statusHost),
            "the host named by the last STATUS notification must be connectable",
        )
        assertFalse(
            VvmImapHostPolicy.canConnectTo("other.example.com"),
            "a host different from the last STATUS host must be refused",
        )
        assertFalse(
            VvmImapHostPolicy.canConnectTo(""),
            "an empty host must be refused",
        )
    }

    @Test
    fun refusesAnyHostBeforeFirstStatus() {
        VvmImapHostPolicy.clear()
        // No STATUS notification seen yet — no mailbox host may be reached.
        assertFalse(
            VvmImapHostPolicy.canConnectTo("mail.example.com"),
            "no host is allowed before the first STATUS notification",
        )
    }

    @Test
    fun onlyMostRecentStatusHostIsAllowed() {
        VvmImapHostPolicy.recordStatusHost("first.example.com")
        VvmImapHostPolicy.recordStatusHost("second.example.com")

        assertTrue(VvmImapHostPolicy.canConnectTo("second.example.com"))
        assertFalse(
            VvmImapHostPolicy.canConnectTo("first.example.com"),
            "a superseded STATUS host must no longer be connectable",
        )
    }
}