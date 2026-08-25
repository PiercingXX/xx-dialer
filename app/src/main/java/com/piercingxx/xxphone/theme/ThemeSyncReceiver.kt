package com.piercingxx.xxphone.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * RECEIVER side of the family-wide theme sync (BRAND-GUIDE §3.3): the
 * xx-launcher broadcasts [ACTION_THEME_CHANGED] — explicitly targeted at this
 * package via `Intent.setPackage`, which Android O+ requires before a
 * manifest-declared receiver sees the broadcast at all — carrying the active
 * preset's display name and its resolved background ARGB.
 *
 * The receiver resolves the name to a [ThemePreset] (or, for "Custom", takes
 * the carried background and runs the shared contrast rule) and persists the
 * resulting [ThemeGround] into the `xx_theme` SharedPreferences, where
 * [ThemeGroundApplier] reads it on every activity resume. Unknown names and a
 * "Custom" broadcast missing its background persist nothing — keeping the
 * ground the user already has beats guessing.
 *
 * Manifest-declared and exported: the sender is another app. The payload is a
 * theme name and a color — worst case a forged broadcast recolors the UI, and
 * no privileged action is reachable through this component (manifest B3 note).
 *
 * Routing and persistence live in the pure [handle] companion over the
 * [ThemeGroundStore] seam, so JVM unit tests drive the full decision path
 * with an in-memory store — no Robolectric, no mocked `Intent` (this repo's
 * unit tests run against the stubbed android.jar; same seam philosophy as
 * Txxt's ThemeSyncReceiver, adapted to a mockless test kit). [onReceive] is
 * extraction glue only.
 */
class ThemeSyncReceiver(
    /**
     * Builds the store the receiver persists into. Defaults to the app's
     * `xx_theme` SharedPreferences — the same store [ThemeGroundApplier]
     * reads; injectable so a test can supply an in-memory store.
     */
    private val storeFactory: (Context) -> ThemeGroundStore = { context ->
        ThemeGroundStore(
            SharedPreferencesThemeKeyValueStore(
                context.getSharedPreferences(ThemeGroundStore.PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    },
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        handle(
            action = intent.action,
            themeName = intent.getStringExtra(EXTRA_THEME_NAME),
            backgroundArgb = if (intent.hasExtra(EXTRA_BACKGROUND)) {
                intent.getIntExtra(EXTRA_BACKGROUND, 0)
            } else {
                null
            },
            store = storeFactory(context),
        )
    }

    companion object {
        /** The xx-launcher's theme-change broadcast action. */
        const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"

        /** String extra: the active preset's display name ("Ocean Drift"). */
        const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"

        /** Int extra: the resolved background ARGB — present even for "Custom". */
        const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"

        /** Display name the launcher sends for its custom color. */
        const val CUSTOM_DISPLAY_NAME = "Custom"

        /**
         * The whole routing decision, pure and JVM-testable: match the
         * action, resolve the named preset — or honor "Custom" through
         * [backgroundArgb] and the shared contrast rule — and persist the
         * ground. Anything unresolvable persists nothing.
         */
        fun handle(
            action: String?,
            themeName: String?,
            backgroundArgb: Int?,
            store: ThemeGroundStore,
        ) {
            if (action != ACTION_THEME_CHANGED) return
            val name = themeName?.trim()?.takeIf { it.isNotEmpty() } ?: return

            if (name.equals(CUSTOM_DISPLAY_NAME, ignoreCase = true)) {
                val background = backgroundArgb ?: return
                store.save(ThemeGround.ofCustom(background), ThemeGroundStore.KEY_CUSTOM)
                return
            }

            val preset = ThemePreset.fromDisplayName(name) ?: return
            store.save(ThemeGround.of(preset), preset.key)
        }
    }
}

/** SharedPreferences adapter for the [ThemeKeyValueStore] seam. */
class SharedPreferencesThemeKeyValueStore(
    private val prefs: SharedPreferences,
) : ThemeKeyValueStore {
    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
