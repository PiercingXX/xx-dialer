package com.piercingxx.xxphone.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [ThemeKeyValueStore] so persistence is JVM-provable. */
private class InMemoryKv : ThemeKeyValueStore {
    private val ints = mutableMapOf<String, Int>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val strings = mutableMapOf<String, String>()

    override fun getInt(key: String): Int? = ints[key]
    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) {
        strings[key] = value
    }
}

/**
 * Verifies the theme-sync receiver's two halves, mirroring Txxt's
 * ThemeSyncWiringTest with this repo's mockless kit: the routing +
 * persistence path runs entirely through the pure [ThemeSyncReceiver.handle]
 * seam over an in-memory store (broadcast dispatch itself needs the platform;
 * `onReceive` is extraction glue only), and the manifest half locks that the
 * OS can actually deliver the launcher's action to a resolvable component.
 */
class ThemeSyncReceiverTest {

    // Gradle unit tests run with the module directory (app/) as the working
    // directory; fall back to the workspace-root-relative path for robustness.
    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    private fun handle(
        action: String? = ThemeSyncReceiver.ACTION_THEME_CHANGED,
        name: String?,
        background: Int? = null,
        kv: InMemoryKv = InMemoryKv(),
    ): InMemoryKv {
        ThemeSyncReceiver.handle(action, name, background, ThemeGroundStore(kv))
        return kv
    }

    // ---- wiring: the manifest declares the receiver for the launcher broadcast ----

    @Test
    fun `manifest declares the theme-sync receiver component`() {
        assertTrue(manifestText.contains(".theme.ThemeSyncReceiver"))
    }

    @Test
    fun `manifest registers the receiver for the launcher theme-changed action`() {
        assertTrue(manifestText.contains(ThemeSyncReceiver.ACTION_THEME_CHANGED))
    }

    @Test
    fun `declared receiver name resolves to a class`() {
        // Throws ClassNotFoundException if the declared name is not real.
        Class.forName("com.piercingxx.xxphone.theme.ThemeSyncReceiver")
    }

    @Test
    fun `broadcast contract literals stay pinned`() {
        assertEquals("xx.launcher.THEME_CHANGED", ThemeSyncReceiver.ACTION_THEME_CHANGED)
        assertEquals("xx.launcher.extra.THEME_NAME", ThemeSyncReceiver.EXTRA_THEME_NAME)
        assertEquals("xx.launcher.extra.BACKGROUND", ThemeSyncReceiver.EXTRA_BACKGROUND)
        assertEquals("Custom", ThemeSyncReceiver.CUSTOM_DISPLAY_NAME)
    }

    // ---- routing: named presets persist their ground ----

    @Test
    fun `a named preset persists its background and dark ground`() {
        val kv = handle(name = "Graphite")
        val store = ThemeGroundStore(kv)
        assertEquals(ThemeGround(0xFF131316.toInt(), lightGround = false), store.load())
        assertEquals("graphite", store.presetKey())
    }

    @Test
    fun `a light preset persists a light ground`() {
        val store = ThemeGroundStore(handle(name = "Paper"))
        assertEquals(ThemeGround(0xFFF3EEE2.toInt(), lightGround = true), store.load())
        assertEquals("paper", store.presetKey())
    }

    @Test
    fun `name matching tolerates casing from across the process boundary`() {
        val store = ThemeGroundStore(handle(name = "ocean drift"))
        assertEquals(ThemeGround(0xFF0F1C2E.toInt(), lightGround = false), store.load())
    }

    @Test
    fun `every preset round-trips through a fresh store`() {
        ThemePreset.entries.forEach { preset ->
            val kv = handle(name = preset.displayName)
            // A brand-new store over the same backing kv must read the ground
            // back — the receiver's write only matters if it is durable.
            val reloaded = ThemeGroundStore(kv).load()
            assertEquals(preset.backgroundArgb, reloaded?.background)
            assertEquals(!preset.isDark, reloaded?.lightGround)
        }
    }

    // ---- routing: Custom is honored via the background extra + contrast rule ----

    @Test
    fun `custom with a dark background persists it with the white foreground`() {
        val custom = 0xFF203040.toInt()
        val store = ThemeGroundStore(handle(name = "Custom", background = custom))
        assertEquals(ThemeGround(custom, lightGround = false), store.load())
        assertEquals(WHITE_FOREGROUND, store.load()?.foreground)
        assertEquals(ThemeGroundStore.KEY_CUSTOM, store.presetKey())
    }

    @Test
    fun `custom with a light background flips to the dark foreground`() {
        val custom = 0xFFF0F0F0.toInt() // luminance 240 > 182
        val store = ThemeGroundStore(handle(name = "custom", background = custom))
        assertEquals(ThemeGround(custom, lightGround = true), store.load())
        assertEquals(DARK_FOREGROUND, store.load()?.foreground)
    }

    @Test
    fun `custom without the background extra persists nothing`() {
        val store = ThemeGroundStore(handle(name = "Custom", background = null))
        assertNull(store.load())
    }

    // ---- routing: everything unresolvable persists nothing ----

    @Test
    fun `a non-theme action persists nothing`() {
        val store = ThemeGroundStore(
            handle(action = "some.other.action", name = "Graphite", background = 0xFF131316.toInt()),
        )
        assertNull(store.load())
        assertNull(store.presetKey())
    }

    @Test
    fun `an unknown preset name persists nothing`() {
        assertNull(ThemeGroundStore(handle(name = "Not A Real Preset")).load())
    }

    @Test
    fun `a missing name persists nothing`() {
        assertNull(ThemeGroundStore(handle(name = null, background = 0xFF000000.toInt())).load())
        assertNull(ThemeGroundStore(handle(name = "   ")).load())
    }

    @Test
    fun `an unresolvable broadcast keeps the ground the user already has`() {
        val kv = handle(name = "Mist")
        handle(name = "Not A Real Preset", kv = kv)
        handle(name = "Custom", background = null, kv = kv)
        // Both bad broadcasts left Mist standing.
        val ground = ThemeGroundStore(kv).load()
        assertEquals(ThemePreset.MIST.backgroundArgb, ground?.background)
        assertTrue(ground!!.lightGround)
    }

    // ---- the ground model itself ----

    @Test
    fun `ground foreground follows the contrast rule`() {
        assertFalse(ThemeGround.ofCustom(0xFF10261B.toInt()).lightGround)
        assertTrue(ThemeGround.ofCustom(0xFFE6EDF5.toInt()).lightGround)
        assertEquals(WHITE_FOREGROUND, ThemeGround.of(ThemePreset.BURGUNDY).foreground)
        assertEquals(DARK_FOREGROUND, ThemeGround.of(ThemePreset.MIST).foreground)
    }
}
