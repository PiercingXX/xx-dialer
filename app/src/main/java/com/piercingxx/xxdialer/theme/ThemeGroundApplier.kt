package com.piercingxx.xxdialer.theme

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.piercingxx.xxdialer.R

/**
 * Applies the persisted launcher-synced [ThemeGround] to a live activity —
 * the GROUND only (window background, system bars, root view) plus, on light
 * grounds, a pragmatic foreground pass so text stays legible. The Signal
 * accent and component styling stay per Theme.XxDialer; dark presets other
 * than AMOLED only shift the ground color, which the existing white-ramp
 * text already reads on.
 *
 * Wired from XxApplication's activity-lifecycle callbacks on every
 * `onResume`, and from [ThemeSyncReceiver] against the resumed activity so a
 * broadcast that lands while Recents/Keypad is already visible repaints in
 * place. No persisted ground (no broadcast has ever landed) leaves the
 * built-in AMOLED theme untouched.
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

        // Two-way ramp: light grounds darken the white type ramp; dark
        // grounds restore the stashed original. Matching on the *current*
        // colour is one-way — after Paper remapped body text to #1A1A1A,
        // AMOLED had nothing white left to put back.
        remapRamp(root, ground.lightGround)
    }

    /**
     * Recursively remap white-ramp text. Each view's ORIGINAL colour is
     * stashed in a tag the first time it is seen (Nope-Mode BrandActivity),
     * and every later decision is made from that rather than from whatever
     * is on screen now.
     */
    private fun remapRamp(view: View, lightGround: Boolean) {
        if (view is TextView) {
            val original = view.getTag(R.id.tag_original_text_color) as? Int
                ?: view.currentTextColor.also { view.setTag(R.id.tag_original_text_color, it) }
            view.setTextColor(remapForeground(original, lightGround))
            view.hintTextColors?.defaultColor?.let { hint ->
                val originalHint = view.getTag(R.id.tag_original_hint_color) as? Int
                    ?: hint.also { view.setTag(R.id.tag_original_hint_color, it) }
                view.setHintTextColor(remapForeground(originalHint, lightGround))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) remapRamp(view.getChildAt(i), lightGround)
        }
    }
}

/**
 * White-ramp remap, pure: light grounds swap white RGB for [DARK_FOREGROUND]
 * at the same alpha; dark grounds return the original. Non-white colours
 * (ink-on-Signal, status) pass through unchanged.
 */
fun remapForeground(original: Int, lightGround: Boolean): Int {
    if (!lightGround) return original
    return darkened(original) ?: original
}

/** [color] moved to the dark foreground keeping its alpha, or null if not white-based. */
internal fun darkened(color: Int): Int? {
    if (color and 0x00FFFFFF != 0x00FFFFFF) return null
    return (color and 0xFF000000.toInt()) or (DARK_FOREGROUND and 0x00FFFFFF)
}
