package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure IMAP decision seams (todo.md D5/D6). The sync worker refuses to open a
 * socket when the carrier requires cellular data and the device is not on a
 * cellular-data transport, and prefers TLS whenever the carrier offers it.
 */
class VvmImapPolicyTest {

    @Test
    fun cellularDataRequiredGatesSync() {
        // Carrier requires cellular data: sync is refused unless we are on a
        // cellular-data transport.
        assertFalse(
            VvmImapPolicy.canSync(cellularDataRequired = true, onCellularData = false),
            "cellular-data-required must refuse sync over a non-cellular transport",
        )
        assertTrue(
            VvmImapPolicy.canSync(cellularDataRequired = true, onCellularData = true),
            "cellular-data-required must allow sync over a cellular-data transport",
        )
    }

    @Test
    fun cellularDataNotRequiredAllowsAnyTransport() {
        // No cellular-data requirement: the mailbox may be reached over any
        // transport, including WiFi.
        assertTrue(
            VvmImapPolicy.canSync(cellularDataRequired = false, onCellularData = false),
            "without a cellular-data requirement, sync must be allowed off cellular",
        )
        assertTrue(
            VvmImapPolicy.canSync(cellularDataRequired = false, onCellularData = true),
            "without a cellular-data requirement, sync must be allowed on cellular",
        )
    }

    @Test
    fun prefersTlsWhenOffered() {
        assertTrue(
            VvmImapPolicy.shouldUseTls(tlsOffered = true),
            "TLS must be used when the carrier offers it",
        )
        assertFalse(
            VvmImapPolicy.shouldUseTls(tlsOffered = false),
            "plaintext fallback is allowed only when TLS is not offered",
        )
    }
}