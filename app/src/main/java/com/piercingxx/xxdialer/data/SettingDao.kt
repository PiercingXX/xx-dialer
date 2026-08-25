package com.piercingxx.xxdialer.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Key-value settings (§11). NOTE — deliberate adaptation of the dictated
 * contract: RulesProvider.kt (landed sibling consumer) reads [all] as a
 * `Map<String, String>` (`getOrDefault(emptyMap())` + `settings[key]`), so
 * `all()` returns the associated map rather than the raw row list.
 */
@Dao
abstract class SettingDao {

    @Query("SELECT * FROM `setting`")
    protected abstract suspend fun entries(): List<SettingEntity>

    suspend fun all(): Map<String, String> = entries().associate { it.key to it.value }

    @Query("SELECT `value` FROM `setting` WHERE `key` = :key LIMIT 1")
    abstract suspend fun get(key: String): String?

    /** REPLACE semantics on the (key) PK (§11). */
    open suspend fun put(key: String, value: String): Unit = putEntry(SettingEntity(key, value))

    @Upsert
    protected abstract suspend fun putEntry(entry: SettingEntity)
}
