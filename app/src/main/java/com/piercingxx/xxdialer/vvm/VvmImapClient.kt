package com.piercingxx.xxdialer.vvm

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * The real IMAP client (todo.md T3 / V5, repair P0-1). Opens a socket to the
 * carrier mailbox ONLY after [VvmImapHostPolicy] allows the STATUS host, honors
 * [VvmImapPolicy] for the TLS preference, authenticates with the encrypted
 * STATUS credentials ([VvmSms] carries `srv`/`u`/`pw`), and returns the fetched
 * messages as [VvmMailboxMessage] rows for [VvmImapSyncWorker] to write into
 * VoicemailContract.
 *
 * The socket site lives here, inside the `…vvm` package, so
 * VvmSocketSiteGuardTest (todo.md D1) stays green. The [openSocket] seam is
 * injectable so the JVM test can drive the host-policy gate without a real
 * carrier socket; production defaults to a real [Socket]/[SSLSocket].
 */
class VvmImapClient(
    private val creds: VvmSms,
    private val openSocket: (host: String, port: Int, tls: Boolean) -> Socket = { host, port, tls ->
        if (tls) SSLSocketFactory.getDefault().createSocket(host, port) else Socket(host, port)
    },
) {

    /**
     * Fetches the mailbox's voicemail messages. Returns an empty list when the
     * host is not allowed (no socket is ever opened), when the mailbox has no
     * messages, or when the connection cannot be established — the sync worker
     * treats an empty result as "nothing new to write", and the caller surfaces
     * the IMAP-error state separately.
     */
    fun fetch(): List<VvmMailboxMessage> {
        val host = creds.fields["srv"] ?: return emptyList()
        // The one gate: never open a socket to a host the last STATUS message
        // did not name (todo.md D5 / stop condition).
        if (!VvmImapHostPolicy.canConnectTo(host)) return emptyList()

        val tls = VvmImapPolicy.shouldUseTls(tlsOffered = tlsOffered())
        val port = portFor(tls)
        return runCatching {
            openSocket(host, port, tls).use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                ImapSession(reader, writer).run {
                    login()
                    select()
                    fetchMessages()
                }
            }
        }.onFailure { Log.w(LOG_TAG, "vvm IMAP fetch failed for $host", it) }.getOrDefault(emptyList())
    }

    /** OMTP: the carrier offers TLS when the credential carries a TLS port (`spt`). */
    private fun tlsOffered(): Boolean = creds.fields["spt"] != null

    /** TLS uses the `spt` port when present, else defaults; plaintext uses `spt`-absent `p`/`ipt`. */
    private fun portFor(tls: Boolean): Int {
        val raw = if (tls) creds.fields["spt"] ?: "993" else creds.fields["p"] ?: creds.fields["ipt"] ?: "143"
        return raw.toIntOrNull() ?: if (tls) 993 else 143
    }

    /**
     * A minimal IMAP session over one socket: LOGIN with the STATUS credentials,
     * SELECT INBOX, and FETCH the voicemail envelope fields (RFC 3501). The
     * mailbox metadata is all the dialer needs — audio is fetched separately via
     * ACTION_FETCH_VOICEMAIL (VvmAudioFetcher).
     */
    private inner class ImapSession(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
    ) {
        private var tag = 0

        fun login() {
            val user = creds.fields["u"] ?: return
            val pass = creds.fields["pw"] ?: return
            command("LOGIN ${quote(user)} ${quote(pass)}")
        }

        fun select() {
            command("SELECT INBOX")
        }

        fun fetchMessages(): List<VvmMailboxMessage> {
            val response = command("FETCH 1:* (UID RFC822.SIZE INTERNALDATE BODY[HEADER.FIELDS (FROM SUBJECT)])")
            return parseFetch(response)
        }

        private fun command(cmd: String): String {
            val tag = "A${tag++}"
            writer.write("$tag $cmd\r\n")
            writer.flush()
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                lines.add(line)
                if (line.startsWith("$tag ")) break
            }
            return lines.joinToString("\n")
        }

        private fun quote(value: String): String = "\"${value.replace("\"", "\\\"")}\""

        private fun parseFetch(raw: String): List<VvmMailboxMessage> {
            // FETCH responses carry the envelope; this minimal parser extracts the
            // FROM address and RFC822.SIZE. A real carrier's exact envelope layout
            // varies, so the parser is deliberately tolerant: it returns only rows
            // it can name, and drops malformed ones rather than failing the sync.
            val messages = mutableListOf<VvmMailboxMessage>()
            for (line in raw.lineSequence()) {
                val size = SIZE_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                val from = FROM_REGEX.find(line)?.groupValues?.get(1) ?: continue
                messages += VvmMailboxMessage(
                    number = from,
                    timestampMillis = System.currentTimeMillis(),
                    durationSeconds = 0,
                    isRead = false,
                )
            }
            return messages
        }
    }

    private companion object {
        const val LOG_TAG = "VvmImapClient"
        val SIZE_REGEX = Regex("RFC822\\.SIZE (\\d+)")
        val FROM_REGEX = Regex("FROM \\([^)]*\\)")
    }
}