package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure OMTP STATUS/SYNC SMS parser (todo.md D5, T1). A valid STATUS body must
 * yield a [VvmSms] with type `STATUS` and the credential fields (`srv`, `u`,
 * `pw`, ...) that a later task persists (T2) and uses to open an IMAP
 * connection (T3/T5). Non-VVM and malformed bodies must yield null.
 */
class VvmSmsParserTest {

    @Test
    fun parsesStatusSmsIntoCredentials() {
        val body = "//VVM:STATUS:srv=mail.example.com;u=alice;pw=s3cret;st=2;rc=1"
        val sms = VvmSmsParser.parse(body)

        assertEquals("STATUS", sms?.type, "a STATUS body must parse to type STATUS")
        assertEquals(
            mapOf(
                "srv" to "mail.example.com",
                "u" to "alice",
                "pw" to "s3cret",
                "st" to "2",
                "rc" to "1",
            ),
            sms?.fields,
            "the STATUS body's credential fields must be captured",
        )
    }

    @Test
    fun parsesSyncSms() {
        val body = "//VVM:SYNC:srv=mail.example.com;u=alice;pw=s3cret;t=1700000000;tuid=42"
        val sms = VvmSmsParser.parse(body)

        assertEquals("SYNC", sms?.type, "a SYNC body must parse to type SYNC")
        assertEquals("mail.example.com", sms?.fields?.get("srv"))
        assertEquals("42", sms?.fields?.get("tuid"))
    }

    @Test
    fun returnsNullForNonVvmBody() {
        assertNull(VvmSmsParser.parse("hello world"), "a non-VVM SMS must not parse")
        assertNull(VvmSmsParser.parse(""), "an empty body must not parse")
    }

    @Test
    fun returnsNullForMalformedBody() {
        // VVM prefix but no known fields — malformed.
        assertNull(VvmSmsParser.parse("//VVM:STATUS:not=known;also=unknown"), "no known fields is malformed")
        // VVM prefix but nothing after it.
        assertNull(VvmSmsParser.parse("//VVM:STATUS:"), "no payload is malformed")
    }

    @Test
    fun ignoresUnknownFieldsButKeepsKnownOnes() {
        val body = "//VVM:STATUS:srv=mail.example.com;unknown=zzz;u=alice"
        val sms = VvmSmsParser.parse(body)
        assertEquals("STATUS", sms?.type)
        assertEquals(mapOf("srv" to "mail.example.com", "u" to "alice"), sms?.fields)
    }
}