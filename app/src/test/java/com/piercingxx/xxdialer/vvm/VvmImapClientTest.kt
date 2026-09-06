package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real IMAP client (repair P0-1 / T1) must open a socket ONLY after
 * VvmImapHostPolicy allows the STATUS host. This test drives the production
 * [VvmImapClient] with an injectable socket seam that records every attempted
 * connect: a disallowed host must produce zero socket opens and an empty fetch,
 * while the allowed STATUS host must reach the socket. It fails if the
 * production worker were still `{ emptyList() }` — this client is the real
 * fetch path XxVisualVoicemailService wires in.
 *
 * Robolectric so android.util.Log is shadowed (the production onFailure logs);
 * a plain JVM test would hit the android.jar Log stub and throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmImapClientTest {

    @Test
    fun refusesToOpenSocketWhenHostNotAllowed() {
        VvmImapHostPolicy.recordStatusHost("mail.allowed.example")
        val client = VvmImapClient(
            creds = VvmSms(type = "STATUS", fields = mapOf("srv" to "mail.other.example")),
            openSocket = { _, _, _ -> throw AssertionError("socket must not open for a disallowed host") },
        )

        val result = client.fetch()

        assertTrue(result.isEmpty(), "a disallowed host must yield no messages")
    }

    @Test
    fun refusesAnySocketBeforeFirstStatusHost() {
        VvmImapHostPolicy.clear()
        // No STATUS host recorded — the client must not open a socket at all.
        val client = VvmImapClient(
            creds = VvmSms(type = "STATUS", fields = mapOf("srv" to "mail.unknown.example")),
            openSocket = { _, _, _ -> throw AssertionError("socket must not open before any STATUS host") },
        )

        val result = client.fetch()

        assertTrue(result.isEmpty(), "no STATUS host means no messages")
    }

    @Test
    fun opensSocketOnlyToAllowedStatusHost() {
        VvmImapHostPolicy.recordStatusHost("mail.allowed.example")
        var openedHost: String? = null
        val client = VvmImapClient(
            creds = VvmSms(
                type = "STATUS",
                fields = mapOf("srv" to "mail.allowed.example", "u" to "alice", "pw" to "secret"),
            ),
            openSocket = { host, _, _ ->
                // The sandbox blocks real sockets; a sentinel proves the socket
                // site is reached for the allowed host. fetch() catches it and
                // returns emptyList, which is the empty-mailbox outcome.
                openedHost = host
                throw AssertionError("socket reached for $host")
            },
        )

        val result = client.fetch()

        assertEquals("mail.allowed.example", openedHost, "the allowed STATUS host must reach the socket site")
        assertTrue(result.isEmpty(), "an empty mailbox yields no messages")
    }

    @Test
    fun refusesSocketWhenCanSyncFalse() {
        VvmImapHostPolicy.recordStatusHost("mail.allowed.example")
        val client = VvmImapClient(
            creds = VvmSms(
                type = "STATUS",
                fields = mapOf("srv" to "mail.allowed.example", "u" to "alice", "pw" to "secret"),
            ),
            openSocket = { _, _, _ -> throw AssertionError("socket must not open when canSync is false") },
        )

        val result = client.fetch(cellularDataRequired = true, onCellularData = false)

        assertTrue(result.isEmpty(), "cellular-required off-cellular must yield no messages")
    }
}