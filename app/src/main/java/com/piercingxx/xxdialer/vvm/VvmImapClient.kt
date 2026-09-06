package com.piercingxx.xxdialer.vvm

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * The real IMAP client (todo.md T3 / V5, repair P0-1). Opens a socket to the
 * carrier mailbox ONLY after [VvmImapHostPolicy] allows the STATUS host, honors
 * [VvmImapPolicy] for the TLS preference and [VvmImapPolicy.canSync] for the
 * transport gate, authenticates with the encrypted STATUS credentials
 * ([VvmSms] carries `srv`/`u`/`pw`), and returns the fetched messages as
 * [VvmMailboxMessage] rows for [VvmImapSyncWorker] to write into
 * VoicemailContract.
 *
 * Host and credentials are never invented: no `srv` / disallowed host / canSync
 * false ⇒ no socket. Audio ([fetchAudio]) is the same gate plus a UID FETCH of
 * BODY.PEEK[]; HAS_CONTENT flips only when those bytes actually arrive.
 */
class VvmImapClient(
    private val creds: VvmSms,
    private val openSocket: (host: String, port: Int, tls: Boolean) -> Socket = { host, port, tls ->
        if (tls) SSLSocketFactory.getDefault().createSocket(host, port) else Socket(host, port)
    },
) {

    /**
     * Fetches the mailbox's voicemail messages. Returns an empty list when the
     * host is not allowed (no socket is ever opened), when [VvmImapPolicy.canSync]
     * refuses the transport, when the mailbox has no messages, or when the
     * connection cannot be established.
     */
    fun fetch(
        cellularDataRequired: Boolean = false,
        onCellularData: Boolean = true,
    ): List<VvmMailboxMessage> {
        if (!VvmImapPolicy.canSync(cellularDataRequired, onCellularData)) return emptyList()
        val host = creds.fields["srv"] ?: return emptyList()
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

    /**
     * Downloads one message's audio by IMAP UID. Returns null when credentials
     * / host / transport / UID are missing, or when the mailbox does not
     * return a body — never invents a host or password.
     */
    fun fetchAudio(
        uid: String,
        cellularDataRequired: Boolean = false,
        onCellularData: Boolean = true,
    ): ByteArray? {
        if (uid.isBlank()) return null
        if (!VvmImapPolicy.canSync(cellularDataRequired, onCellularData)) return null
        val host = creds.fields["srv"] ?: return null
        if (!VvmImapHostPolicy.canConnectTo(host)) return null
        val user = creds.fields["u"] ?: return null
        val pass = creds.fields["pw"] ?: return null
        val tls = VvmImapPolicy.shouldUseTls(tlsOffered = tlsOffered())
        val port = portFor(tls)
        return runCatching {
            openSocket(host, port, tls).use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                imapReadAsciiLine(input)
                imapWriteCmd(output, "A0 LOGIN ${imapQuote(user)} ${imapQuote(pass)}")
                imapReadUntilTagged(input, "A0")
                imapWriteCmd(output, "A1 SELECT INBOX")
                imapReadUntilTagged(input, "A1")
                imapWriteCmd(output, "A2 UID FETCH $uid (BODY.PEEK[])")
                val raw = imapReadFetchLiteral(input, "A2")
                imapExtractAudio(raw)
            }
        }.onFailure { Log.w(LOG_TAG, "vvm IMAP audio fetch failed for $host uid=$uid", it) }.getOrNull()
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
     * SELECT INBOX, and FETCH the voicemail envelope fields (RFC 3501). Audio
     * is fetched separately via [fetchAudio] (VvmAudioFetcher / VvmFetchHandler).
     */
    private inner class ImapSession(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
    ) {
        private var tag = 0

        fun login() {
            val user = creds.fields["u"] ?: return
            val pass = creds.fields["pw"] ?: return
            command("LOGIN ${imapQuote(user)} ${imapQuote(pass)}")
        }

        fun select() {
            command("SELECT INBOX")
        }

        fun fetchMessages(): List<VvmMailboxMessage> {
            val response = command("FETCH 1:* (UID RFC822.SIZE INTERNALDATE BODY[HEADER.FIELDS (FROM SUBJECT)])")
            return imapParseFetch(response)
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
    }

    private companion object {
        const val LOG_TAG = "VvmImapClient"
    }
}

private val IMAP_UID_REGEX = Regex("UID (\\d+)")
private val IMAP_FROM_HEADER_REGEX = Regex("(?im)^From:\\s*<?([+\\d][^>\\s\\r\\n]*)")
private val IMAP_LITERAL_REGEX = Regex("\\{(\\d+)}\\s*$")

private fun imapQuote(value: String): String = "\"${value.replace("\"", "\\\"")}\""

private fun imapWriteCmd(output: OutputStream, line: String) {
    output.write("$line\r\n".toByteArray(Charsets.US_ASCII))
    output.flush()
}

private fun imapReadAsciiLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) break
        if (b != '\r'.code) sb.append(b.toChar())
    }
    return sb.toString()
}

private fun imapReadUntilTagged(input: InputStream, tag: String): String {
    val lines = mutableListOf<String>()
    while (true) {
        val line = imapReadAsciiLine(input) ?: break
        lines.add(line)
        if (line.startsWith("$tag ")) break
    }
    return lines.joinToString("\n")
}

private fun imapReadFetchLiteral(input: InputStream, tag: String): ByteArray {
    val collected = ByteArrayOutputStream()
    while (true) {
        val line = imapReadAsciiLine(input) ?: break
        val literal = IMAP_LITERAL_REGEX.find(line)
        if (literal != null) {
            val n = literal.groupValues[1].toInt()
            val bytes = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(bytes, off, n - off)
                if (r < 0) break
                off += r
            }
            collected.write(bytes, 0, off)
            continue
        }
        if (line.startsWith("$tag ")) break
    }
    return collected.toByteArray()
}

private fun imapExtractAudio(rfc822: ByteArray): ByteArray? {
    if (rfc822.isEmpty()) return null
    val latin1 = rfc822.toString(Charsets.ISO_8859_1)
    val audioHeader = Regex("(?im)^Content-Type:\\s*audio/").find(latin1)
    if (audioHeader != null) {
        val headerStart = audioHeader.range.first
        val rest = latin1.substring(headerStart)
        val blank = rest.indexOf("\r\n\r\n").takeIf { it >= 0 }
            ?: rest.indexOf("\n\n").let { if (it >= 0) it else -1 }
        if (blank < 0) return null
        val sepLen = if (rest.startsWith("\r\n\r\n", blank)) 4 else 2
        val bodyStart = headerStart + blank + sepLen
        val boundary = Regex("(?im)^--").find(latin1, bodyStart)?.range?.first ?: rfc822.size
        if (boundary <= bodyStart) return null
        return rfc822.copyOfRange(bodyStart, boundary)
    }
    if (latin1.startsWith("From:") || latin1.startsWith("MIME-Version:", ignoreCase = true)) {
        val blank = latin1.indexOf("\r\n\r\n").takeIf { it >= 0 }
            ?: latin1.indexOf("\n\n").let { if (it >= 0) it else -1 }
        if (blank < 0) return null
        val sepLen = if (latin1.startsWith("\r\n\r\n", blank)) 4 else 2
        val body = rfc822.copyOfRange(blank + sepLen, rfc822.size)
        return body.takeIf { it.isNotEmpty() }
    }
    return rfc822
}

private fun imapParseFetch(raw: String): List<VvmMailboxMessage> {
    val messages = mutableListOf<VvmMailboxMessage>()
    val blocks = raw.split(Regex("(?=\\* \\d+ FETCH)"))
    for (block in blocks) {
        if (!block.contains("FETCH", ignoreCase = true)) continue
        val from = IMAP_FROM_HEADER_REGEX.find(block)?.groupValues?.get(1) ?: continue
        val uid = IMAP_UID_REGEX.find(block)?.groupValues?.get(1)
        messages += VvmMailboxMessage(
            number = from.trim(),
            timestampMillis = System.currentTimeMillis(),
            durationSeconds = 0,
            isRead = false,
            sourceData = uid,
        )
    }
    return messages
}
