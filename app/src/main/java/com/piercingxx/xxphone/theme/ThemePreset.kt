package com.piercingxx.xxphone.theme

/**
 * The seven named background presets of the family theme sync, mirroring the
 * xx-launcher's theme set (BRAND-GUIDE §3.3) and Txxt's `ThemePreset`. Pure
 * Kotlin — no `android.*` imports — so the model and the contrast rule are
 * JVM-testable without a device.
 *
 * Names and background values are the brand's own, reused verbatim so theme
 * auto-sync with the launcher can match by display name.
 */
enum class ThemePreset(
    /** Stable identifier used in persisted settings. */
    val key: String,
    /** Display name carried by the launcher broadcast, e.g. "AMOLED Night". */
    val displayName: String,
    /** Background color as a 0xAARRGGBB long. */
    val background: Long,
    /** Whether the preset is a dark theme (white foreground ramp). */
    val isDark: Boolean,
) {
    AMOLED_NIGHT("amoled-night", "AMOLED Night", 0xFF000000, true),
    GRAPHITE("graphite", "Graphite", 0xFF131316, true),
    FOREST_NIGHT("forest-night", "Forest Night", 0xFF10261B, true),
    OCEAN_DRIFT("ocean-drift", "Ocean Drift", 0xFF0F1C2E, true),
    BURGUNDY("burgundy", "Burgundy", 0xFF2A1018, true),
    PAPER("paper", "Paper", 0xFFF3EEE2, false),
    MIST("mist", "Mist", 0xFFE6EDF5, false);

    /** Background as an ARGB Int, the form Android color APIs take. */
    val backgroundArgb: Int get() = background.toInt()

    companion object {
        /** The default preset (AMOLED Night — the brand's default ground). */
        val DEFAULT: ThemePreset = AMOLED_NIGHT

        /**
         * Resolve a preset by its stable [key]. Returns null for an unknown
         * key so callers can fall back to [DEFAULT] without throwing.
         */
        fun fromKey(key: String?): ThemePreset? =
            entries.firstOrNull { it.key == key }

        /**
         * Resolve a preset by its display name, case-insensitively and
         * whitespace-tolerantly: the name crossed a process boundary, and a
         * casing tweak on the launcher side should not silently break sync.
         * Returns null for anything unrecognised — including the launcher's
         * "Custom", which is honored via the broadcast's background extra
         * instead (see ThemeSyncReceiver).
         */
        fun fromDisplayName(name: String?): ThemePreset? {
            val wanted = name?.trim() ?: return null
            return entries.firstOrNull { it.displayName.equals(wanted, ignoreCase = true) }
        }
    }
}

/** Near-black foreground for light grounds — the family's shared value. */
const val DARK_FOREGROUND: Int = 0xFF1A1A1A.toInt()

/** White foreground for dark grounds. */
const val WHITE_FOREGROUND: Int = 0xFFFFFFFF.toInt()

/**
 * The family-wide contrast rule, identical across every receiver and the
 * launcher — a different threshold would put two apps on opposite sides of
 * the decision for a mid-tone background. Luminance is the classic
 * 0.299r + 0.587g + 0.114b; strictly above 182 counts as a light ground.
 */
fun isLightGround(background: Int): Boolean {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    val luminance = 0.299 * r + 0.587 * g + 0.114 * b
    return luminance > 182
}

/** White on dark grounds, near-black (#FF1A1A1A) on light ones. */
fun contrastForeground(background: Int): Int =
    if (isLightGround(background)) DARK_FOREGROUND else WHITE_FOREGROUND
