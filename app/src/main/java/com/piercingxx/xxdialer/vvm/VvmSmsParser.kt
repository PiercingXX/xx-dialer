package com.piercingxx.xxdialer.vvm

/**
 * Pure OMTP Visual Voicemail SMS parser (todo.md D5). A VVM notification SMS
 * arrives as a `//VVM:STATUS:` or `//VVM:SYNC:` line followed by `key=value`
 * pairs using the OMTP field names. This parser extracts those fields into a
 * [VvmSms] credential record so a later task can persist them (T2) and open an
 * IMAP connection to the mailbox host (T3/T5). It is deliberately pure: no
 * Android types, no I/O, so it is trivially unit-testable and safe to call from
 * any thread.
 */
object VvmSmsParser {

    /** OMTP field names the spec defines for STATUS/SYNC notifications. */
    private val KNOWN_FIELDS = setOf(
        "srv", "ipt", "u", "pw", "spt", "su", "sp", "sub", "dn", "s",
        "st", "rc", "n", "t", "tuid", "fuid", "tst",
    )

    private const val STATUS_PREFIX = "//VVM:STATUS:"
    private const val SYNC_PREFIX = "//VVM:SYNC:"

    /**
     * Parses [body] into a [VvmSms] when it is a well-formed VVM STATUS or SYNC
     * notification; returns null for any non-VVM or malformed input.
     *
     * A body is accepted only when it starts with `//VVM:STATUS:` or
     * `//VVM:SYNC:`. The remainder is split on `;`, each segment is split on
     * `=` into a known field name and its value; unknown or malformed segments
     * are ignored. A body that yields no known fields is treated as malformed
     * and returns null.
     */
    fun parse(body: String): VvmSms? {
        val type = when {
            body.startsWith(STATUS_PREFIX) -> "STATUS"
            body.startsWith(SYNC_PREFIX) -> "SYNC"
            else -> return null
        }

        val fields = LinkedHashMap<String, String>()
        val payload = body.removePrefix(if (type == "STATUS") STATUS_PREFIX else SYNC_PREFIX)
        for (segment in payload.split(';')) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue // no '=' or empty key — malformed segment
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (key !in KNOWN_FIELDS) continue
            fields[key] = value
        }

        if (fields.isEmpty()) return null
        return VvmSms(type = type, fields = fields)
    }
}

/**
 * A parsed VVM credential record. [type] is `STATUS` or `SYNC`; [fields] maps
 * the known OMTP field names to their values (e.g. `srv` = IMAP host, `u` =
 * username, `pw` = password).
 */
data class VvmSms(val type: String, val fields: Map<String, String>)