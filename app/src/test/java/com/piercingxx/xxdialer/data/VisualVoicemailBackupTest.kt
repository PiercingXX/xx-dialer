package com.piercingxx.xxdialer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Backup whitelist gate for the opt-in VVM toggle (todo.md D1: "Backup JSON
 * whitelist the key"). KEY_VISUAL_VOICEMAIL rides along in an export as a
 * user-owned setting, so an import must be able to restore it — otherwise a
 * restore would silently drop the user's opt-in choice back to off. It must be
 * in IMPORTABLE_SETTING_KEYS, and the round-trip must preserve the value.
 */
class VisualVoicemailBackupTest {

    @Test
    fun `KEY_VISUAL_VOICEMAIL is whitelisted for import`() {
        val result = BackupJson.sanitizeImportedSettings(
            mapOf(SettingsRepository.KEY_VISUAL_VOICEMAIL to "0"),
        )
        assertTrue(
            result.isSuccess,
            "the opt-in VVM toggle must be importable from a backup",
        )
    }

    @Test
    fun `export round-trip preserves the opt-in toggle value`() {
        val json = BackupJson.toJson(
            BackupJson.Payload(
                tiers = emptyList(),
                patterns = emptyList(),
                settings = mapOf(SettingsRepository.KEY_VISUAL_VOICEMAIL to "1"),
            ),
        )
        val parsed = BackupJson.parse(json).getOrThrow()!!
        assertTrue(BackupJson.validate(parsed).isSuccess)
        val sanitized = BackupJson.sanitizeImportedSettings(parsed.settings).getOrThrow()
        assertEquals(
            "1",
            sanitized[SettingsRepository.KEY_VISUAL_VOICEMAIL],
            "a user who opted in must keep their choice across a restore",
        )
    }
}