package com.piercingxx.xxphone.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Import config guard (R11/D13, §15): a backup is user DATA, never a mandate.
 * Unknown setting keys fail closed naming themselves; enforcement_mode is
 * forced to observing no matter what the payload says — importing may never
 * raise enforcement ("offered, never flipped silently").
 */
class BackupImportGuardTest {

    @Test
    fun `crafted enforcing mode lands observing`() {
        val sanitized = BackupJson.sanitizeImportedSettings(
            mapOf(SettingsRepository.KEY_ENFORCEMENT_MODE to "enforcing"),
        ).getOrThrow()
        assertEquals("observing", sanitized[SettingsRepository.KEY_ENFORCEMENT_MODE])
    }

    @Test
    fun `enforcing survives export round-trip but never import`() {
        val json = BackupJson.toJson(
            BackupJson.Payload(
                tiers = emptyList(),
                patterns = emptyList(),
                settings = mapOf(SettingsRepository.KEY_ENFORCEMENT_MODE to "enforcing"),
            ),
        )
        val parsed = BackupJson.parse(json).getOrThrow()!!
        assertTrue(BackupJson.validate(parsed).isSuccess) // the export shape is legal...
        val sanitized = BackupJson.sanitizeImportedSettings(parsed.settings).getOrThrow()
        assertEquals("observing", sanitized[SettingsRepository.KEY_ENFORCEMENT_MODE]) // ...import demotes anyway
    }

    @Test
    fun `payload missing enforcement key gains observing`() {
        val sanitized = BackupJson.sanitizeImportedSettings(emptyMap()).getOrThrow()
        assertEquals("observing", sanitized[SettingsRepository.KEY_ENFORCEMENT_MODE])
    }

    @Test
    fun `every shipped settings key passes the whitelist`() {
        // bypass_until ships absent from defaults yet is user-owned (§7.1)
        val keys = SettingsRepository.designDefaults(0L).keys +
            SettingsRepository.KEY_BYPASS_UNTIL + SettingsRepository.KEY_ENFORCEMENT_MODE
        keys.forEach { key ->
            assertTrue(
                BackupJson.sanitizeImportedSettings(mapOf(key to "x")).isSuccess,
                "$key should be importable",
            )
        }
    }

    @Test
    fun `unknown key fails closed naming itself`() {
        val result = BackupJson.sanitizeImportedSettings(
            mapOf(SettingsRepository.KEY_ENFORCEMENT_MODE to "observing", "sneaky_override" to "1"),
        )
        assertTrue(result.isFailure, "unknown keys must reject the import")
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("sneaky_override"), "failure must name the key, was: $message")
    }

    @Test
    fun `several unknown keys are all named sorted`() {
        val result = BackupJson.sanitizeImportedSettings(
            mapOf("zzz_bad" to "2", "aaa_bad" to "1"),
        )
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("aaa_bad") && message.contains("zzz_bad"))
        assertTrue(message.indexOf("aaa_bad") < message.indexOf("zzz_bad"), "sorted for stable diffs")
    }
}
