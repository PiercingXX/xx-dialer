package com.piercingxx.xxdialer.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The preset model is the receiver half of a cross-app contract (BRAND-GUIDE
 * §3.3): names, background values, and the 182-luminance contrast rule must
 * match the launcher's exactly, or the family drifts apart on a mid-tone.
 */
class ThemePresetTest {

    // ---- display-name resolution: all seven, unknown, case-insensitivity ----

    @Test
    fun `every display name resolves to its preset`() {
        assertEquals(ThemePreset.AMOLED_NIGHT, ThemePreset.fromDisplayName("AMOLED Night"))
        assertEquals(ThemePreset.GRAPHITE, ThemePreset.fromDisplayName("Graphite"))
        assertEquals(ThemePreset.FOREST_NIGHT, ThemePreset.fromDisplayName("Forest Night"))
        assertEquals(ThemePreset.OCEAN_DRIFT, ThemePreset.fromDisplayName("Ocean Drift"))
        assertEquals(ThemePreset.BURGUNDY, ThemePreset.fromDisplayName("Burgundy"))
        assertEquals(ThemePreset.PAPER, ThemePreset.fromDisplayName("Paper"))
        assertEquals(ThemePreset.MIST, ThemePreset.fromDisplayName("Mist"))
    }

    @Test
    fun `display-name matching is case-insensitive and trims whitespace`() {
        assertEquals(ThemePreset.OCEAN_DRIFT, ThemePreset.fromDisplayName("ocean drift"))
        assertEquals(ThemePreset.AMOLED_NIGHT, ThemePreset.fromDisplayName("AMOLED NIGHT"))
        assertEquals(ThemePreset.MIST, ThemePreset.fromDisplayName("  Mist  "))
    }

    @Test
    fun `unknown, blank, and null names resolve to nothing`() {
        assertNull(ThemePreset.fromDisplayName("Not A Real Preset"))
        assertNull(ThemePreset.fromDisplayName("Custom")) // honored via BACKGROUND, not here
        assertNull(ThemePreset.fromDisplayName(""))
        assertNull(ThemePreset.fromDisplayName(null))
    }

    @Test
    fun `keys resolve and the default is AMOLED Night`() {
        ThemePreset.entries.forEach { assertEquals(it, ThemePreset.fromKey(it.key)) }
        assertNull(ThemePreset.fromKey("no-such-key"))
        assertNull(ThemePreset.fromKey(null))
        assertEquals(ThemePreset.AMOLED_NIGHT, ThemePreset.DEFAULT)
    }

    // ---- values stay pinned to the launcher contract ----

    @Test
    fun `background values stay pinned to the family contract`() {
        assertEquals(0xFF000000, ThemePreset.AMOLED_NIGHT.background)
        assertEquals(0xFF131316, ThemePreset.GRAPHITE.background)
        assertEquals(0xFF10261B, ThemePreset.FOREST_NIGHT.background)
        assertEquals(0xFF0F1C2E, ThemePreset.OCEAN_DRIFT.background)
        assertEquals(0xFF2A1018, ThemePreset.BURGUNDY.background)
        assertEquals(0xFFF3EEE2, ThemePreset.PAPER.background)
        assertEquals(0xFFE6EDF5, ThemePreset.MIST.background)
    }

    @Test
    fun `dark and light split matches the contract`() {
        assertTrue(ThemePreset.AMOLED_NIGHT.isDark)
        assertTrue(ThemePreset.GRAPHITE.isDark)
        assertTrue(ThemePreset.FOREST_NIGHT.isDark)
        assertTrue(ThemePreset.OCEAN_DRIFT.isDark)
        assertTrue(ThemePreset.BURGUNDY.isDark)
        assertFalse(ThemePreset.PAPER.isDark)
        assertFalse(ThemePreset.MIST.isDark)
    }

    // ---- the shared contrast rule: 0.299r + 0.587g + 0.114b > 182 ----

    @Test
    fun `every preset background lands on its contract foreground`() {
        ThemePreset.entries.forEach { preset ->
            val expected = if (preset.isDark) WHITE_FOREGROUND else DARK_FOREGROUND
            assertEquals(
                expected,
                contrastForeground(preset.backgroundArgb),
                "contrast foreground for ${preset.displayName}",
            )
        }
    }

    @Test
    fun `luminance strictly above 182 flips to the dark foreground`() {
        // Uniform grays weight to exactly their channel value
        // (0.299 + 0.587 + 0.114 = 1), so 0xB7 = 183 sits just past the line.
        val gray183 = 0xFFB7B7B7.toInt()
        assertTrue(isLightGround(gray183))
        assertEquals(DARK_FOREGROUND, contrastForeground(gray183))
    }

    @Test
    fun `luminance at or below 182 keeps the white foreground`() {
        // 0xB6 = 182 exactly: the rule is strictly greater-than.
        val gray182 = 0xFFB6B6B6.toInt()
        assertFalse(isLightGround(gray182))
        assertEquals(WHITE_FOREGROUND, contrastForeground(gray182))
    }

    @Test
    fun `foreground constants stay pinned to the family values`() {
        assertEquals(0xFF1A1A1A.toInt(), DARK_FOREGROUND)
        assertEquals(0xFFFFFFFF.toInt(), WHITE_FOREGROUND)
    }
}
