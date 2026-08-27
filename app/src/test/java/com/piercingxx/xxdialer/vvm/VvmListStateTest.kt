package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure honest-state decision for the voicemail list (todo.md VVM list, T1).
 * The list must never lie to the user: every raw mailbox input maps to exactly one
 * honest state, in precedence order — no carrier config, then not-yet-activated,
 * then network revoked, then IMAP error, then empty, then the real list.
 */
class VvmListStateTest {

    @Test
    fun decidesHonestState() {
        // No carrier config wins over everything — the mailbox cannot be reached.
        assertEquals(
            VvmListState.NoCarrierConfig,
            VvmListState.decide(
                carrierConfigValid = false,
                activated = true,
                networkRevoked = false,
                imapError = false,
                voicemailCount = 3,
            ),
            "an invalid carrier config must show NoCarrierConfig even with voicemails present",
        )

        // Not yet activated is the next most honest state.
        assertEquals(
            VvmListState.Activating,
            VvmListState.decide(
                carrierConfigValid = true,
                activated = false,
                networkRevoked = false,
                imapError = false,
                voicemailCount = 0,
            ),
            "a valid config that is not yet activated must show Activating",
        )

        // Network revoked is honest about the carrier cutting access.
        assertEquals(
            VvmListState.NetworkRevoked,
            VvmListState.decide(
                carrierConfigValid = true,
                activated = true,
                networkRevoked = true,
                imapError = false,
                voicemailCount = 0,
            ),
            "a revoked network must show NetworkRevoked, not an empty list",
        )

        // An IMAP error means the mailbox could not be read.
        assertEquals(
            VvmListState.ImapError,
            VvmListState.decide(
                carrierConfigValid = true,
                activated = true,
                networkRevoked = false,
                imapError = true,
                voicemailCount = 0,
            ),
            "an IMAP error must show ImapError, not an empty list",
        )

        // Reachable and healthy but empty.
        assertEquals(
            VvmListState.Empty,
            VvmListState.decide(
                carrierConfigValid = true,
                activated = true,
                networkRevoked = false,
                imapError = false,
                voicemailCount = 0,
            ),
            "a healthy empty mailbox must show Empty",
        )

        // The one honest "there is something to show" state.
        val list = VvmListState.decide(
            carrierConfigValid = true,
            activated = true,
            networkRevoked = false,
            imapError = false,
            voicemailCount = 3,
        )
        assertTrue(list is VvmListState.List, "a healthy non-empty mailbox must show List")
        assertEquals(3, (list as VvmListState.List).voicemailCount, "List must carry the voicemail count")
    }
}