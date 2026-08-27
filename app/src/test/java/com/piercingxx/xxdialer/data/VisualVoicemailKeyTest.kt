package com.piercingxx.xxdialer.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Typed accessor for the opt-in Visual voicemail flag (§12). The stored value
 * is a "0"/"1" string; [SettingsRepository.visualVoicemailEnabled] must read
 * "1" as on and anything else (including absent, which ships as the day-zero
 * state) as off — the dialer holds no network sockets until the toggle flips.
 */
class VisualVoicemailKeyTest {

    private class FakeDao(private val store: MutableMap<String, String>) : SettingDao() {
        override suspend fun get(key: String): String? = store[key]
        override suspend fun putEntry(entry: SettingEntity) {
            store[entry.key] = entry.value
        }

        override suspend fun entries(): List<SettingEntity> =
            store.map { SettingEntity(it.key, it.value) }
    }

    @Test
    fun visualVoicemailEnabledReadsOneAsOn() = runBlocking {
        val repo = SettingsRepository(FakeDao(mutableMapOf(KEY to "1")))
        assertTrue(repo.visualVoicemailEnabled(), "a stored \"1\" must read as on")
    }

    @Test
    fun `visualVoicemailEnabled reads zero as off`() = runBlocking {
        val repo = SettingsRepository(FakeDao(mutableMapOf(KEY to "0")))
        assertFalse(repo.visualVoicemailEnabled(), "the shipped opt-in default must read as off")
    }

    @Test
    fun `visualVoicemailEnabled fails open to off when absent`() = runBlocking {
        val repo = SettingsRepository(FakeDao(mutableMapOf()))
        assertFalse(repo.visualVoicemailEnabled(), "absent must read as off (fail-open, §15)")
    }

    private companion object {
        const val KEY = SettingsRepository.KEY_VISUAL_VOICEMAIL
    }
}