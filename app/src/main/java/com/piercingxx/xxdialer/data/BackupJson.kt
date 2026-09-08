package com.piercingxx.xxdialer.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.piercingxx.xxdialer.core.Mode

/**
 * Gson backup/restore of user-owned facts only (§11): tiers, patterns,
 * settings (windows live inside settings values). NOT the screen log, NOT the
 * mirror — both rebuild. Envelope is versioned; import validates everything
 * before a single row moves, then writes inside one transaction so a bad file
 * can never leave half a restore behind.
 *
 * Import config guard (R11/D13): a backup is user DATA, never a mandate.
 * Settings keys are whitelisted (unknown keys fail closed, naming themselves)
 * and `enforcement_mode` is forced back to observing no matter what the
 * payload says — enforcement is offered, never flipped silently (§15).
 */
object BackupJson {

    const val SCHEMA = 1
    const val ACTION_BLOCK = "block"
    const val ACTION_SILENCE = "silence"
    const val TIER_BIZ = "biz"

    private val gson = Gson()

    /** The full user-owned payload, independent of transport. */
    data class Payload(
        val tiers: List<TierMemberEntity>,
        val patterns: List<PatternRuleEntity>,
        val settings: Map<String, String>,
    )

    // ---- export ------------------------------------------------------------

    suspend fun export(db: XxDatabase): String = toJson(
        Payload(
            tiers = db.tierMemberDao().all(),
            patterns = db.patternRuleDao().allOnce(),
            settings = db.settingDao().all(),
        ),
    )

    // ---- import ------------------------------------------------------------

    suspend fun import(db: XxDatabase, json: String): Result<Unit> =
        parse(json).mapCatching { payload ->
            requireNotNull(payload) { "backup envelope is null" }
            validate(payload).getOrThrow()
            val settings = sanitizeImportedSettings(payload.settings).getOrThrow()
            db.withTransaction {
                // Wholesale replace for Room-owned tables; settings merge so
                // keys added post-export keep their shipped defaults (§15).
                db.tierMemberDao().deleteAll()
                payload.tiers.forEach { db.tierMemberDao().upsert(it) }
                db.patternRuleDao().deleteAll()
                payload.patterns.forEach { db.patternRuleDao().insert(it) }
                settings.forEach { (key, value) -> db.settingDao().put(key, value) }
            }
        }

    // ---- import config guard (pure seam, JVM-tested) -------------------------

    /**
     * Setting keys an imported backup may set: every shipped default key plus
     * the app-owned bookkeeping keys that legitimately ride along in an export.
     * Anything else is fail-closed — the import refuses loudly rather than
     * writing unvetted configuration.
     */
    internal val IMPORTABLE_SETTING_KEYS = setOf(
        SettingsRepository.KEY_ENFORCEMENT_MODE,
        SettingsRepository.KEY_OBSERVE_WEEK_END,
        SettingsRepository.KEY_BYPASS_UNTIL,
        SettingsRepository.KEY_BYPASS_DURATION_MINUTES,
        SettingsRepository.KEY_UNKNOWN_WINDOW,
        SettingsRepository.KEY_BUSINESS_WINDOW,
        SettingsRepository.KEY_HIDDEN_CALLER_POLICY,
        SettingsRepository.KEY_STIR_ACTION,
        SettingsRepository.KEY_REPEAT_CALLER_ENABLED,
        SettingsRepository.KEY_SILENCED_NOTIF_POLICY,
        SettingsRepository.KEY_ANSWER_INTERACTION,
        SettingsRepository.KEY_GROUP_RECENTS,
        SettingsRepository.KEY_VISUAL_VOICEMAIL, // opt-in VVM toggle (§12, D1)
        SettingsRepository.KEY_RING_REPEAT,
        "daily_silence_digest", // ring/SilencedNotifier daily-summary stash
        "canned_reply_1",       // ui/IncomingCallActivity canned replies
        "canned_reply_2",
        "canned_reply_3",
        "last_tier_prune",      // data/ContactMirror prune stamp
    )

    /**
     * The R11 gate on imported settings: unknown keys reject with a failure
     * naming them (fail-closed for config, loud), and `enforcement_mode` is
     * FORCED to observing regardless of the payload — a crafted backup may
     * never raise enforcement ("offered, never flipped silently", §15).
     */
    internal fun sanitizeImportedSettings(raw: Map<String, String>): Result<Map<String, String>> =
        runCatching {
            val unknown = raw.keys - IMPORTABLE_SETTING_KEYS
            require(unknown.isEmpty()) {
                "setting: unknown key(s) ${unknown.sorted().joinToString(", ")}"
            }
            raw + (SettingsRepository.KEY_ENFORCEMENT_MODE to Mode.OBSERVING.name.lowercase())
        }

    // ---- pure seams (JVM-tested) --------------------------------------------

    /** Wire format: {"schema":1,"tiers":[...],"patterns":[...],"settings":{...}} */
    internal data class Envelope(
        @SerializedName("schema") val schema: Int?,
        @SerializedName("tiers") val tiers: List<StoredTier>?,
        @SerializedName("patterns") val patterns: List<StoredPattern>?,
        @SerializedName("settings") val settings: Map<String, String>?,
    )

    /** Nullable on purpose: Gson skips absent fields regardless of Kotlin defaults. */
    internal data class StoredTier(
        @SerializedName("lookupKey") val lookupKey: String? = null,
        @SerializedName("tier") val tier: String? = null,
        @SerializedName("addedAt") val addedAt: Long? = null,
    )

    internal data class StoredPattern(
        @SerializedName("e164Prefix") val e164Prefix: String? = null,
        @SerializedName("wildcards") val wildcards: Int? = null,
        @SerializedName("action") val action: String? = null,
        @SerializedName("preset") val preset: String? = null,
        @SerializedName("createdAt") val createdAt: Long? = null,
    )

    internal fun toJson(p: Payload): String =
        gson.toJson(
            Envelope(
                schema = SCHEMA,
                tiers = p.tiers.map {
                    StoredTier(it.lookupKey, it.tier, it.addedAt)
                },
                patterns = p.patterns.map {
                    StoredPattern(it.e164Prefix, it.wildcards, it.action, it.preset, it.createdAt)
                },
                settings = p.settings,
            ),
        )

    /** Garbage in ⇒ Result.failure; never a half-parsed payload. */
    internal fun parse(json: String): Result<Payload?> = runCatching {
        val env = gson.fromJson(json.trim(), Envelope::class.java)
            ?: return Result.success(null)
        require(env.schema == SCHEMA) { "schema ${env.schema} unsupported (want $SCHEMA)" }
        Payload(
            tiers = env.tiers.orEmpty().map {
                TierMemberEntity(
                    lookupKey = it.lookupKey.orEmpty(),
                    tier = it.tier.orEmpty(),
                    addedAt = it.addedAt ?: 0L,
                )
            },
            patterns = env.patterns.orEmpty().map {
                PatternRuleEntity(
                    id = 0, // ids regenerate on insert (§11 autoGenerate)
                    e164Prefix = it.e164Prefix.orEmpty(),
                    wildcards = it.wildcards ?: 0,
                    action = it.action.orEmpty(),
                    preset = it.preset,
                    createdAt = it.createdAt ?: 0L,
                )
            },
            settings = env.settings.orEmpty(),
        )
    }

    /** Structural validation before any write; first violation names itself. */
    internal fun validate(p: Payload?): Result<Unit> = runCatching {
        requireNotNull(p) { "payload is null" }
        p.tiers.forEachIndexed { i, t ->
            require(t.lookupKey.isNotBlank()) { "tier[$i]: blank lookupKey" }
            require(t.tier.isNotBlank() && t.tier != "star") { "tier[$i]: bad tier '${t.tier}'" }
        }
        p.patterns.forEachIndexed { i, r ->
            require(r.e164Prefix.isNotBlank()) { "pattern[$i]: blank prefix" }
            require(r.wildcards >= 0) { "pattern[$i]: negative wildcards ${r.wildcards}" }
            require(r.action == ACTION_BLOCK || r.action == ACTION_SILENCE) {
                "pattern[$i]: unknown action '${r.action}'"
            }
        }
        p.settings.forEach { (key, value) ->
            require(key.isNotBlank()) { "setting: blank key (value='$value')" }
        }
    }
}
