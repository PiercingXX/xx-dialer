package com.piercingxx.xxdialer.data

import com.piercingxx.xxdialer.core.RingRepeatPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ring-repeat is user-owned: a restore must keep once / twice / until-voicemail. */
class RingRepeatBackupTest {

    @Test
    fun key_is_whitelisted_for_import() {
        val result = BackupJson.sanitizeImportedSettings(
            mapOf(SettingsRepository.KEY_RING_REPEAT to RingRepeatPolicy.TOKEN_TWICE),
        )
        assertTrue(result.isSuccess)
        assertEquals(
            RingRepeatPolicy.TOKEN_TWICE,
            result.getOrThrow()[SettingsRepository.KEY_RING_REPEAT],
        )
    }
}
