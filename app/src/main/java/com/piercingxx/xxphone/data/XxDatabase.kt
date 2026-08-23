package com.piercingxx.xxphone.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The FactStore (WS4, §11). Version 1 only — there are no migrations and no
 * destructive fallback: a future schema change ships a real migration.
 */
@Database(
    entities = [
        TierMemberEntity::class,
        PatternRuleEntity::class,
        ContactMirrorEntity::class,
        ScreenLogEntity::class,
        ChannelRegistryEntity::class,
        SettingEntity::class,
        EmergencyMarkerEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class XxDatabase : RoomDatabase() {

    abstract fun tierMemberDao(): TierMemberDao

    abstract fun patternRuleDao(): PatternRuleDao

    abstract fun contactMirrorDao(): ContactMirrorDao

    abstract fun screenLogDao(): ScreenLogDao

    abstract fun channelRegistryDao(): ChannelRegistryDao

    abstract fun settingDao(): SettingDao

    abstract fun emergencyMarkerDao(): EmergencyMarkerDao

    companion object {
        const val NAME = "xxphone.db"
    }
}
