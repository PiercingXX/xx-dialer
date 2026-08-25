package com.piercingxx.xxdialer

import android.content.Context
import com.piercingxx.xxdialer.data.ContactMirror
import com.piercingxx.xxdialer.data.PatternRuleDao
import com.piercingxx.xxdialer.data.RulesProvider
import com.piercingxx.xxdialer.data.SettingDao
import com.piercingxx.xxdialer.data.SettingsRepository
import com.piercingxx.xxdialer.data.XxDatabase
import com.piercingxx.xxdialer.data.XxDb
import com.piercingxx.xxdialer.ring.ChannelRegistry
import com.piercingxx.xxdialer.telecom.EmergencyMarker

/**
 * Process-wide singletons, built lazily on first use and cached for the life
 * of the process (design §14). Deliberately dumb: application context in,
 * instance out, double-checked lazy under the hood. Nothing here does I/O.
 */
object ServiceLocator {

    @Volatile private var dbInstance: XxDatabase? = null
    @Volatile private var settingsInstance: SettingsRepository? = null
    @Volatile private var rulesInstance: RulesProvider? = null
    @Volatile private var emergencyMarkerInstance: EmergencyMarker? = null
    @Volatile private var channelRegistryInstance: ChannelRegistry? = null
    @Volatile private var contactMirrorInstance: ContactMirror? = null

    fun db(context: Context): XxDatabase =
        dbInstance ?: synchronized(this) {
            dbInstance ?: XxDb.build(context.applicationContext).also { dbInstance = it }
        }

    fun settings(context: Context): SettingsRepository =
        settingsInstance ?: synchronized(this) {
            settingsInstance ?: SettingsRepository(db(context).settingDao())
                .also { settingsInstance = it }
        }

    fun rules(context: Context): RulesProvider =
        rulesInstance ?: synchronized(this) {
            val db = db(context)
            rulesInstance
                ?: RulesProvider(
                    db.settingDao(),
                    db.patternRuleDao(),
                ).also { rulesInstance = it }
        }

    /** telecom/ owns the class; we only hold the process-wide handle. */
    fun emergencyMarker(context: Context): EmergencyMarker =
        emergencyMarkerInstance ?: synchronized(this) {
            emergencyMarkerInstance ?: EmergencyMarker(db(context))
                .also { emergencyMarkerInstance = it }
        }

    /**
     * ONE mirror engine per process (§9): the observer registration and its
     * debounce state live on this instance, so throwaway constructions would
     * silently drop the observer with them.
     */
    fun contactMirror(context: Context): ContactMirror =
        contactMirrorInstance ?: synchronized(this) {
            contactMirrorInstance ?: ContactMirror(context.applicationContext, db(context))
                .also { contactMirrorInstance = it }
        }

    fun channelRegistry(context: Context): ChannelRegistry =
        channelRegistryInstance ?: synchronized(this) {
            channelRegistryInstance ?: ChannelRegistry(
                context.applicationContext,
                db(context).channelRegistryDao(),
            ).also { channelRegistryInstance = it }
        }
}
