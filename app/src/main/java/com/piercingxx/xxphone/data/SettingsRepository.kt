package com.piercingxx.xxphone.data

import com.google.gson.Gson
import com.piercingxx.xxphone.core.HiddenCallerPolicy
import com.piercingxx.xxphone.core.Mode
import com.piercingxx.xxphone.core.StirAction
import com.piercingxx.xxphone.core.Window
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Typed access over the `setting` table (design §11). The canonical keys and
 * their first-run defaults live here so there is exactly one place that knows
 * what a setting is called and what it says on day zero.
 *
 * Every accessor fails toward its design default (§15): a corrupt or missing
 * value reads as if unset — noisy, never lossy, never throwing.
 */
class SettingsRepository(private val dao: SettingDao) {

    suspend fun getString(key: String): String? = dao.get(key)

    suspend fun setString(key: String, value: String) = dao.put(key, value)

    /** OBSERVING until the user explicitly flips the switch (§12 Rules screen). */
    suspend fun enforcementMode(): Mode = safeEnum(dao.get(KEY_ENFORCEMENT_MODE), Mode.OBSERVING)

    suspend fun setEnforcementMode(mode: Mode) {
        dao.put(KEY_ENFORCEMENT_MODE, mode.name.lowercase())
    }

    suspend fun observeWeekEndMillis(): Long? = epochOf(dao.get(KEY_OBSERVE_WEEK_END))

    suspend fun bypassUntilMillis(): Long? = epochOf(dao.get(KEY_BYPASS_UNTIL))

    /**
     * Null clears the bypass. The dictated DAO contract has no delete, so
     * absence is spelled as an empty string — [epochOf] reads blank as absent.
     */
    suspend fun setBypassUntil(epochMillis: Long?) =
        dao.put(KEY_BYPASS_UNTIL, epochMillis?.toString().orEmpty())

    suspend fun bypassDurationMinutes(): Int =
        epochOf(dao.get(KEY_BYPASS_DURATION_MINUTES))?.toInt() ?: DEFAULT_BYPASS_MINUTES

    suspend fun silencedNotifPolicy(): String =
        dao.get(KEY_SILENCED_NOTIF_POLICY)?.takeIf { it in NOTIF_POLICIES }
            ?: DEFAULT_NOTIF_POLICY

    /**
     * Enforcement is *offered*, never flipped silently (§15 observe-week-end):
     * true only while still observing and past the seeded week boundary.
     */
    suspend fun shouldOfferEnforcement(nowEpochMillis: Long): Boolean =
        shouldOfferEnforcement(enforcementMode(), nowEpochMillis, observeWeekEndMillis())

    /** Put-if-absent seeding; existing values are never overwritten (upgrade installs). */
    suspend fun ensureDefaults(defaults: Map<String, String>) =
        defaults.forEach { (key, value) ->
            if (dao.get(key) == null) dao.put(key, value)
        }

    companion object {

        const val KEY_ENFORCEMENT_MODE = "enforcement_mode"
        const val KEY_OBSERVE_WEEK_END = "observe_week_end"
        const val KEY_BYPASS_UNTIL = "bypass_until"
        const val KEY_BYPASS_DURATION_MINUTES = "bypass_duration_minutes"
        const val KEY_UNKNOWN_WINDOW = "unknown_window"
        const val KEY_BUSINESS_WINDOW = "business_window"
        const val KEY_HIDDEN_CALLER_POLICY = "hidden_caller_policy"
        const val KEY_STIR_ACTION = "stir_action"
        const val KEY_REPEAT_CALLER_ENABLED = "repeat_caller_enabled"
        const val KEY_SILENCED_NOTIF_POLICY = "silence_notif_policy"
        const val KEY_ANSWER_INTERACTION = "answer_interaction"
        const val KEY_GROUP_RECENTS = "group_recents"

        private val gson = Gson()
        private const val DEFAULT_BYPASS_MINUTES = 120
        private const val OBSERVE_WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private const val DEFAULT_NOTIF_POLICY = "immediate"
        private val NOTIF_POLICIES = setOf("immediate", "daily", "never")

        /** Value Setup seeds when its final step completes (§12): now + 7 days. */
        fun observeWeekEndSeed(nowEpochMillis: Long): String =
            (nowEpochMillis + OBSERVE_WEEK_MS).toString()

        /**
         * Seeded once per install by XxApplication.onCreate (§12). Deliberately
         * ABSENT here: [KEY_OBSERVE_WEEK_END] — the observe-week clock starts
         * when Setup's final step completes ([observeWeekEndSeed] via the
         * SetupActivity DONE path), not at process launch, so a user stalling
         * mid-setup never burns the week. bypass_until is also absent —
         * Expecting a call starts off (§7.1).
         */
        fun designDefaults(nowEpochMillis: Long): Map<String, String> = mapOf(
            KEY_ENFORCEMENT_MODE to Mode.OBSERVING.name.lowercase(),
            KEY_BYPASS_DURATION_MINUTES to DEFAULT_BYPASS_MINUTES.toString(),
            KEY_UNKNOWN_WINDOW to windowToJson(Window(9 * 60, 17 * 60, Window.ALL_DAYS)),
            KEY_BUSINESS_WINDOW to windowToJson(Window(9 * 60, 19 * 60, Window.ALL_DAYS)),
            KEY_HIDDEN_CALLER_POLICY to HiddenCallerPolicy.UNKNOWN.name,
            KEY_STIR_ACTION to StirAction.BLOCK.name,
            KEY_REPEAT_CALLER_ENABLED to "1",
            KEY_SILENCED_NOTIF_POLICY to DEFAULT_NOTIF_POLICY,
            KEY_ANSWER_INTERACTION to "tap",
            KEY_GROUP_RECENTS to "1",
        )

        /** {"startMinute":540,"endMinute":1020,"daysMask":127} ↔ core.Window (§7). */
        fun windowToJson(window: Window): String =
            gson.toJson(
                StoredWindow(window.startMinuteOfDay, window.endMinuteOfDay, window.daysMask),
            )

        fun parseWindow(json: String?): Window? {
            val raw = json?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val stored = runCatching { gson.fromJson(raw, StoredWindow::class.java) }.getOrNull()
                ?: return null
            return runCatching {
                Window(stored.startMinute, stored.endMinute, stored.daysMask)
            }.getOrNull()
        }

        /** Unknown or garbage enum text falls back to [default] (fail-open). */
        inline fun <reified T : Enum<T>> safeEnum(raw: String?, default: T): T =
            raw?.trim()?.let { candidate ->
                runCatching { enumValueOf<T>(candidate.uppercase()) }.getOrNull()
            } ?: default

        fun shouldOfferEnforcement(
            mode: Mode,
            nowEpochMillis: Long,
            observeWeekEndMillis: Long?,
        ): Boolean = mode == Mode.OBSERVING &&
            observeWeekEndMillis != null &&
            nowEpochMillis >= observeWeekEndMillis

        internal fun epochToDateTime(epochMillis: Long?): LocalDateTime? =
            epochMillis?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
            }

        private fun epochOf(raw: String?): Long? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    }

    /** Gson shape of a persisted window — field names ARE the JSON keys. */
    private data class StoredWindow(
        val startMinute: Int = 0,
        val endMinute: Int = 0,
        val daysMask: Int = 0,
    )
}
