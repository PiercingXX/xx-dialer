package com.piercingxx.xxdialer.theme

/**
 * The GROUND a synced theme resolves to: a background color plus whether it
 * is light enough to need the dark foreground ramp. This is the whole scope
 * of the family sync for xx-dialer (as for Nope-Mode's BackgroundTheme):
 * background + contrast text only — the Signal accent and component styling
 * stay the app's own.
 *
 * Pure Kotlin so persistence and resolution are JVM-testable.
 */
data class ThemeGround(
    /** Background ARGB color. */
    val background: Int,
    /** True when the ground is light and needs the dark foreground. */
    val lightGround: Boolean,
) {
    /** The foreground the contrast rule pairs with this ground. */
    val foreground: Int get() = if (lightGround) DARK_FOREGROUND else WHITE_FOREGROUND

    companion object {
        /** The ground a [ThemePreset] resolves to. */
        fun of(preset: ThemePreset): ThemeGround =
            ThemeGround(preset.backgroundArgb, !preset.isDark)

        /** The ground an arbitrary (Custom) background resolves to. */
        fun ofCustom(background: Int): ThemeGround =
            ThemeGround(background, isLightGround(background))
    }
}

/**
 * Minimal key-value seam over SharedPreferences so [ThemeGroundStore] is
 * JVM-testable with an in-memory map (mirrors Txxt's ThemeKeyValueStore).
 */
interface ThemeKeyValueStore {
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * Persists the synced [ThemeGround] (plus the preset key, for anyone who
 * later wants to know which named preset it came from). Pure Kotlin over the
 * [ThemeKeyValueStore] seam; the SharedPreferences adapter lives with
 * [ThemeSyncReceiver].
 */
class ThemeGroundStore(private val kv: ThemeKeyValueStore) {

    /** Persist [ground]; [presetKey] is a named preset's key or [KEY_CUSTOM]. */
    fun save(ground: ThemeGround, presetKey: String) {
        kv.putInt(KEY_BACKGROUND, ground.background)
        kv.putBoolean(KEY_LIGHT_GROUND, ground.lightGround)
        kv.putString(KEY_PRESET, presetKey)
    }

    /** The persisted ground, or null when no broadcast has ever landed. */
    fun load(): ThemeGround? {
        val background = kv.getInt(KEY_BACKGROUND) ?: return null
        return ThemeGround(background, kv.getBoolean(KEY_LIGHT_GROUND, false))
    }

    /** The persisted preset key ("graphite", "custom", ...), or null. */
    fun presetKey(): String? = kv.getString(KEY_PRESET)

    companion object {
        /** SharedPreferences file the store is wired over in production. */
        const val PREFS_NAME = "xx_theme"

        const val KEY_BACKGROUND = "ground_background"
        const val KEY_LIGHT_GROUND = "ground_light"
        const val KEY_PRESET = "ground_preset"

        /** Preset-key sentinel for the launcher's "Custom" theme. */
        const val KEY_CUSTOM = "custom"
    }
}
