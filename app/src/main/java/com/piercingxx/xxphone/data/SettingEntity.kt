package com.piercingxx.xxphone.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Key-value store behind windows, enforcement mode, bypasses, policies (§11).
 * Values are strings; typed parsing lives in [SettingsRepository][com.piercingxx.xxphone.data.SettingsRepository].
 */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
