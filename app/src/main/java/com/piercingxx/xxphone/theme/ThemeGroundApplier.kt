package com.piercingxx.xxphone.theme

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowCompat

/**
 * Applies the persisted launcher-synced [ThemeGround] to a live activity —
 * the GROUND only (window background, system bars, root view) plus, on light
 * grounds, a pragmatic foreground pass so text stays legible. The Signal
 * accent and component styling stay per Theme.XxPhone; dark presets other
 * than AMOLED only shift the ground color, which the existing white-ramp
 * text already reads on.
 *
 * Wired from XxApplication's activity-lifecycle callbacks on every
 * `onResume`, so a broadcast landing while the app is backgrounded repaints
 * the next time any screen shows. No persisted ground (no broadcast has ever
 * landed) leaves the built-in AMOLED theme untouched.
 *
 * Scope limits, deliberately (the same scope as Nope-Mode's BackgroundTheme):
 * the light-ground foreground pass remaps the white type ramp on the views
 * present at resume time; rows a RecyclerView inflates later, drawable-drawn
 * glyphs, and the emphasis-invert blocks (Signal-white bg, ink text — still
 * legible on light grounds) keep the app's own styling.
 */
object ThemeGroundApplier {

    /** The persisted ground, or null when no launcher broadcast has landed. */
    fun load(context: Context): ThemeGround? =
        ThemeGroundStore(
            SharedPreferencesThemeKeyValueStore(
                context.getSharedPreferences(ThemeGroundStore.PREFS_NAME, Context.MODE_PRIVATE),
            ),
        ).load()

    /** Repaint [activity]'s ground from the persisted sync; no-op without one. */
    fun apply(activity: Activity) {
        val ground = load(activity) ?: return
        val window = activity.window ?: return

        // Window + system bars: the ground bleeds edge-to-edge like the
        // built-in theme's pxx_ink does.
        window.setBackgroundDrawable(ColorDrawable(ground.background))
        @Suppress("DEPRECATION") // still the Views-stack way to paint bars on 31–35
        run {
            window.statusBarColor = ground.background
            window.navigationBarColor = ground.background
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = ground.lightGround
            isAppearanceLightNavigationBars = ground.lightGround
        }

        // Root view: activity layouts are background-less columns over the
        // window, but painting the content root also covers any screen that
        // sets its own ink ground.
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        root.setBackgroundColor(ground.background)

        // Light grounds flip the foreground ramp: every white-based text
        // color becomes the dark foreground at the same opacity stop, so the
        // 90/80/50/25 hierarchy survives the flip.
        if (ground.lightGround) remapWhiteRamp(root)
    }

    /**
     * Recursively remap white-ramp text ([WHITE_FOREGROUND] RGB at any alpha)
     * to [DARK_FOREGROUND] at the same alpha. Non-white text — ink-on-Signal
     * emphasis, status colors — is left alone.
     */
    private fun remapWhiteRamp(view: View) {
        if (view is TextView) {
            view.setTextColor(darkened(view.currentTextColor) ?: view.currentTextColor)
            view.hintTextColors?.defaultColor?.let { hint ->
                darkened(hint)?.let { view.setHintTextColor(it) }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) remapWhiteRamp(view.getChildAt(i))
        }
    }

    /** [color] moved to the dark foreground keeping its alpha, or null if not white-based. */
    private fun darkened(color: Int): Int? {
        if (color and 0x00FFFFFF != 0x00FFFFFF) return null
        return (color and 0xFF000000.toInt()) or (DARK_FOREGROUND and 0x00FFFFFF)
    }
}
