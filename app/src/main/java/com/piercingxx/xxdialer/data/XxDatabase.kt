package com.piercingxx.xxdialer.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The FactStore (WS4, §11). No destructive fallback: every bump ships a real
 * migration. v2: contact_mirror PK is (lookupKey, e164) so a contact's second
 * number is still saved at screening.
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
    version = 2,
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
        // File name inside the app's private databases/ dir. Renamed with the
        // package (com.piercingxx.xxdialer): the new application id gets a
        // fresh data directory, so there is no xxphone.db on disk to migrate
        // from and no compatibility reason to keep the old name.
        const val NAME = "xxdialer.db"
    }
}
