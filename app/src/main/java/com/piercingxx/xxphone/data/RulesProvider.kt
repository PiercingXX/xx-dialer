package com.piercingxx.xxphone.data

import com.piercingxx.xxphone.core.HiddenCallerPolicy
import com.piercingxx.xxphone.core.PatternRule
import com.piercingxx.xxphone.core.Rules
import com.piercingxx.xxphone.core.StirAction
import com.piercingxx.xxphone.core.Window

/**
 * Settings → core.[Rules] assembly. Pure mapping: the only suspension is
 * fetching the stored rows, and nothing here can throw — every parse failure
 * degrades to that field's shipped default (§15: fail open toward ringing).
 */
class RulesProvider(
    private val settingDao: SettingDao,
    private val patternRuleDao: PatternRuleDao,
) {

    suspend fun current(): Rules {
        val stored = runCatching { settingDao.all() }.getOrDefault(emptyMap())
        val patternRows = runCatching { patternRuleDao.allOnce() }.getOrDefault(emptyList())
        return assemble(stored, patternRows)
    }

    companion object {

        private val DEFAULT_UNKNOWN = Window(9 * 60, 17 * 60, Window.ALL_DAYS)  // §7
        private val DEFAULT_BUSINESS = Window(9 * 60, 19 * 60, Window.ALL_DAYS) // §7
        private const val ACTION_BLOCK = "block"
        private const val ACTION_SILENCE = "silence"

        /** Pure seam: stored strings + pattern rows → policy rules. */
        internal fun assemble(
            settings: Map<String, String>,
            patternRows: List<PatternRuleEntity>,
        ): Rules = Rules(
            unknownWindow =
                window(settings, SettingsRepository.KEY_UNKNOWN_WINDOW, DEFAULT_UNKNOWN),
            businessWindow =
                window(settings, SettingsRepository.KEY_BUSINESS_WINDOW, DEFAULT_BUSINESS),
            hiddenCallerPolicy = SettingsRepository.safeEnum(
                settings[SettingsRepository.KEY_HIDDEN_CALLER_POLICY],
                HiddenCallerPolicy.UNKNOWN,
            ),
            stirAction = SettingsRepository.safeEnum(
                settings[SettingsRepository.KEY_STIR_ACTION],
                StirAction.BLOCK,
            ),
            repeatCallerEnabled = settings[SettingsRepository.KEY_REPEAT_CALLER_ENABLED] != "0",
            bypassUntil = SettingsRepository.epochToDateTime(epochOf(settings[SettingsRepository.KEY_BYPASS_UNTIL])),
            blockPatterns = patterns(patternRows, ACTION_BLOCK),
            silencePatterns = patterns(patternRows, ACTION_SILENCE),
        )

        private fun window(settings: Map<String, String>, key: String, fallback: Window): Window =
            SettingsRepository.parseWindow(settings[key]) ?: fallback

        /**
         * core.PatternRule takes digits-only prefixes. A corrupt row is
         * SKIPPED, never rewritten: stripping non-digits would silently turn
         * malformed input into a DIFFERENT valid rule (L6 — "+1 425-555"
         * becoming prefix 1425555), and a rule the user never wrote is a rule
         * that can block, which D5 forbids. Negative wildcards fail
         * PatternRule's require and are skipped the same way.
         */
        private fun patterns(rows: List<PatternRuleEntity>, action: String): List<PatternRule> =
            rows.asSequence()
                .filter { it.action == action }
                .mapNotNull { row ->
                    val prefix = row.e164Prefix.trim()
                    if (prefix.isEmpty() || !prefix.all(Char::isDigit)) {
                        return@mapNotNull null // corrupt ⇒ skip, don't launder into another rule (L6)
                    }
                    runCatching { PatternRule(prefix, row.wildcards) }.getOrNull()
                }
                .toList()

        private fun epochOf(raw: String?): Long? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    }
}
