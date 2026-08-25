package com.piercingxx.xxdialer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BackupJson pure seams on the JVM (§11): envelope round-trip, schema gate,
 * and validate-before-write. Room itself needs a device (AMBIGUOUS-on-JVM);
 * everything under test here is Gson + plain data classes.
 */
class BackupJsonRoundTripTest {

    private val payload = BackupJson.Payload(
        tiers = listOf(
            TierMemberEntity("abc123/", "biz", 1_700_000_000_000),
            TierMemberEntity("def456/", "biz", 1_700_000_001_000),
        ),
        patterns = listOf(
            PatternRuleEntity(1, "1425555", 4, "block", null, 1L),
            PatternRuleEntity(2, "1800555", 4, "silence", "neighbor_spoof", 2L),
        ),
        settings = mapOf(
            "enforcement_mode" to "observing",
            "unknown_window" to """{"startMinute":540,"endMinute":1020,"daysMask":127}""",
            "business_window" to """{"startMinute":540,"endMinute":1140,"daysMask":31}""",
        ),
    )

    @Test
    fun `round trip preserves every user-owned fact`() {
        val json = BackupJson.toJson(payload)
        val parsed = BackupJson.parse(json).getOrThrow()

        assertEquals(BackupJson.SCHEMA, 1)
        assertEquals(payload.tiers, parsed!!.tiers)
        // ids regenerate on import, so compare the identity-bearing fields
        assertEquals(
            payload.patterns.map { Triple(it.e164Prefix, it.wildcards, it.action) },
            parsed.patterns.map { Triple(it.e164Prefix, it.wildcards, it.action) },
        )
        assertEquals("neighbor_spoof", parsed.patterns[1].preset)
        assertEquals(payload.settings, parsed.settings)
    }

    @Test
    fun `envelope carries schema version 1`() {
        val json = BackupJson.toJson(payload)
        assertTrue(json.contains(""""schema":${BackupJson.SCHEMA}"""))
    }

    @Test
    fun `wrong schema version is rejected`() {
        val bad = BackupJson.toJson(payload).replace("\"schema\":1", "\"schema\":99")
        assertTrue(BackupJson.parse(bad).isFailure)
    }

    @Test
    fun `garbage json fails to parse instead of throwing`() {
        assertTrue(BackupJson.parse("{ not json").isFailure)
        // Empty input reads as a null envelope (Gson 2.x) — never a usable
        // payload, so validation rejects it and nothing gets written (§15).
        val empty = BackupJson.parse("")
        assertTrue(empty.isSuccess)
        assertNull(empty.getOrNull())
        assertTrue(BackupJson.validate(empty.getOrNull()).isFailure)
    }

    @Test
    fun `null envelope parses to null payload and fails validation`() {
        val parsed = BackupJson.parse("null").getOrNull()
        assertNull(parsed)
        assertTrue(BackupJson.validate(parsed).isFailure)
    }

    @Test
    fun `unknown action is rejected before any write`() {
        val broken = payload.copy(
            patterns = listOf(PatternRuleEntity(0, "1425555", 4, "obliterate", null, 1L)),
        )
        val result = BackupJson.validate(BackupJson.parse(BackupJson.toJson(broken)).getOrThrow())
        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `negative wildcards and blank prefix are rejected`() {
        val negativeMask = payload.copy(
            patterns = listOf(PatternRuleEntity(0, "1425555", -1, "block", null, 1L)),
        )
        assertTrue(BackupJson.validate(negativeMask).isFailure)

        val blankPrefix = payload.copy(
            patterns = listOf(PatternRuleEntity(0, "", 4, "block", null, 1L)),
        )
        assertTrue(BackupJson.validate(blankPrefix).isFailure)
    }

    @Test
    fun `non-business tier membership is rejected`() {
        val wrongTier = payload.copy(tiers = listOf(TierMemberEntity("abc123/", "family", 1L)))
        assertTrue(BackupJson.validate(wrongTier).isFailure)

        val blankKey = payload.copy(tiers = listOf(TierMemberEntity("", "biz", 1L)))
        assertTrue(BackupJson.validate(blankKey).isFailure)
    }

    @Test
    fun `valid payload passes validation`() {
        assertTrue(BackupJson.validate(payload).isSuccess)
        val reparsed = BackupJson.parse(BackupJson.toJson(payload)).getOrThrow()
        assertTrue(BackupJson.validate(reparsed).isSuccess)
    }
}
