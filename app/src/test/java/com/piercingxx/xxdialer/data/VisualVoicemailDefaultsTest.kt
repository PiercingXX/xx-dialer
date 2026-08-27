package com.piercingxx.xxdialer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * First-run shape of the Visual voicemail opt-in flag (§12).
 *
 * VVM is OPT-IN: it ships off ("0") and only the user flipping the toggle
 * turns it on. The dialer is offline by default — no network sockets are ever
 * opened until this flag reads non-zero — so the day-zero value must be off,
 * not merely absent. Absent would also read as off (fail-open), but an
 * explicit "0" in designDefaults is the contract: it makes the opt-in state
 * visible in the seeded row set and prevents a future default flip from
 * silently shipping VVM on.
 */
class VisualVoicemailDefaultsTest {

    @Test
    fun `visual voicemail ships off by default`() {
        val defaults = SettingsRepository.designDefaults(nowEpochMillis = 0L)
        assertEquals(
            "0",
            defaults[SettingsRepository.KEY_VISUAL_VOICEMAIL],
            "VVM must seed as off (opt-in): the dialer holds no network sockets until the toggle is flipped",
        )
    }

    @Test
    fun `visual voicemail key is present in the seeded defaults`() {
        val defaults = SettingsRepository.designDefaults(nowEpochMillis = 0L)
        assertTrue(
            defaults.containsKey(SettingsRepository.KEY_VISUAL_VOICEMAIL),
            "the opt-in flag must be explicitly seeded, not merely fail-open on absence",
        )
    }

    @Test
    fun `visual voicemail is distinct from the other opt-in defaults`() {
        // Guard against a copy/paste default: VVM off must not collide with a
        // "1"-defaulted toggle like group_recents.
        val defaults = SettingsRepository.designDefaults(nowEpochMillis = 0L)
        assertEquals("1", defaults[SettingsRepository.KEY_GROUP_RECENTS])
        assertFalse(
            defaults[SettingsRepository.KEY_VISUAL_VOICEMAIL] == "1",
            "VVM must not inherit a default-on value",
        )
    }
}