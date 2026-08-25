package com.piercingxx.xxdialer.data

import com.piercingxx.xxdialer.core.HiddenCallerPolicy
import com.piercingxx.xxdialer.core.Mode
import com.piercingxx.xxdialer.core.StirAction
import com.piercingxx.xxdialer.core.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM checks for the settings parsing seams (design §7, §12, §15). */
class SettingsParsingTest {

    // --- window JSON round-trip -------------------------------------------------

    @Test
    fun `default unknown window round-trips`() {
        val window = Window(9 * 60, 17 * 60, Window.ALL_DAYS)
        assertEquals(window, SettingsRepository.parseWindow(SettingsRepository.windowToJson(window)))
    }

    @Test
    fun `wrapping window round-trips`() {
        val window = Window(21 * 60, 5 * 60, 0b0000011)
        assertEquals(window, SettingsRepository.parseWindow(SettingsRepository.windowToJson(window)))
    }

    @Test
    fun `stored json uses the canonical field names`() {
        assertEquals(
            """{"startMinute":540,"endMinute":1020,"daysMask":127}""",
            SettingsRepository.windowToJson(Window(540, 1020, 127)),
        )
    }

    @Test
    fun `garbage json parses to null not throw`() {
        assertNull(SettingsRepository.parseWindow("not json"))
        assertNull(SettingsRepository.parseWindow("""{"startMinute":"x"}"""))
        assertNull(SettingsRepository.parseWindow(null))
        assertNull(SettingsRepository.parseWindow(""))
        // Out-of-range fields must fail Window's invariants, not crash.
        assertNull(SettingsRepository.parseWindow("""{"startMinute":99999,"endMinute":1020,"daysMask":127}"""))
        assertNull(SettingsRepository.parseWindow("""{"startMinute":540,"endMinute":1020,"daysMask":0}"""))
    }

    @Test
    fun `designDefaults windows deserialize to the shipped windows`() {
        val defaults = SettingsRepository.designDefaults(nowEpochMillis = 0L)
        assertEquals(Window(540, 1020, 127), SettingsRepository.parseWindow(defaults[SettingsRepository.KEY_UNKNOWN_WINDOW]))
        assertEquals(Window(540, 1140, 127), SettingsRepository.parseWindow(defaults[SettingsRepository.KEY_BUSINESS_WINDOW]))
    }

    // --- valueOf-safe enum parsing ----------------------------------------------

    @Test
    fun `mode parses case-insensitively and tolerates surrounding whitespace`() {
        assertEquals(Mode.ENFORCING, SettingsRepository.safeEnum("enforcing", Mode.OBSERVING))
        assertEquals(Mode.OBSERVING, SettingsRepository.safeEnum("OBSERVING", Mode.OBSERVING))
        assertEquals(Mode.OBSERVING, SettingsRepository.safeEnum(null, Mode.OBSERVING))
        assertEquals(Mode.OBSERVING, SettingsRepository.safeEnum("junk", Mode.OBSERVING))
        // Trimmed then uppercased first, so padded input still parses as ENFORCING
        // rather than falling back — only unknown/garbage text fails open (§15).
        assertEquals(Mode.ENFORCING, SettingsRepository.safeEnum(" enforcing ", Mode.OBSERVING))
    }

    @Test
    fun `hidden caller policy falls back to UNKNOWN on garbage`() {
        assertEquals(HiddenCallerPolicy.SILENCE, SettingsRepository.safeEnum("silence", HiddenCallerPolicy.UNKNOWN))
        assertEquals(HiddenCallerPolicy.BLOCK, SettingsRepository.safeEnum("BLOCK", HiddenCallerPolicy.UNKNOWN))
        assertEquals(HiddenCallerPolicy.UNKNOWN, SettingsRepository.safeEnum("whisper", HiddenCallerPolicy.UNKNOWN))
        assertEquals(HiddenCallerPolicy.UNKNOWN, SettingsRepository.safeEnum(null, HiddenCallerPolicy.UNKNOWN))
    }

    @Test
    fun `stir action falls back to BLOCK on garbage`() {
        assertEquals(StirAction.OFF, SettingsRepository.safeEnum("off", StirAction.BLOCK))
        assertEquals(StirAction.BLOCK, SettingsRepository.safeEnum("nonsense", StirAction.BLOCK))
    }

    // --- observe-week-end boundaries (§15: offered, never flipped silently) ------

    private val weekEnd = 1_000_000L

    @Test
    fun `enforcement offered exactly at the week end`() {
        assertTrue(
            SettingsRepository.shouldOfferEnforcement(Mode.OBSERVING, weekEnd, weekEnd),
            "now == end is past-or-equal: offering",
        )
    }

    @Test
    fun `enforcement not offered before the week end`() {
        assertFalse(SettingsRepository.shouldOfferEnforcement(Mode.OBSERVING, weekEnd - 1, weekEnd))
    }

    @Test
    fun `enforcement offered after the week end even if the user ignored it forever`() {
        assertTrue(SettingsRepository.shouldOfferEnforcement(Mode.OBSERVING, weekEnd + 1, weekEnd))
        assertTrue(SettingsRepository.shouldOfferEnforcement(Mode.OBSERVING, weekEnd + 30L * 24 * 60 * 60 * 1000, weekEnd))
    }

    @Test
    fun `never offered while enforcing or when unseeded`() {
        assertFalse(SettingsRepository.shouldOfferEnforcement(Mode.ENFORCING, weekEnd + 1, weekEnd))
        assertFalse(SettingsRepository.shouldOfferEnforcement(Mode.OBSERVING, weekEnd, null))
    }

    // --- first-run seeding shape --------------------------------------------------

    @Test
    fun `defaults seed observing mode without starting the observe week`() {
        val now = 777_777_777_777L
        val defaults = SettingsRepository.designDefaults(now)
        assertEquals("observing", defaults[SettingsRepository.KEY_ENFORCEMENT_MODE])
        assertFalse(
            defaults.containsKey(SettingsRepository.KEY_OBSERVE_WEEK_END),
            "the observe week starts when Setup completes (§12), never at process launch",
        )
        assertEquals("120", defaults[SettingsRepository.KEY_BYPASS_DURATION_MINUTES])
        assertEquals("UNKNOWN", defaults[SettingsRepository.KEY_HIDDEN_CALLER_POLICY])
        assertEquals("BLOCK", defaults[SettingsRepository.KEY_STIR_ACTION])
        assertEquals("immediate", defaults[SettingsRepository.KEY_SILENCED_NOTIF_POLICY])
        assertFalse(defaults.containsKey(SettingsRepository.KEY_BYPASS_UNTIL), "bypass_until ships absent")
    }

    @Test
    fun `setup-completion seed value is now plus seven days`() {
        val now = 777_777_777_777L
        assertEquals(
            (now + 7L * 24 * 60 * 60 * 1000).toString(),
            SettingsRepository.observeWeekEndSeed(now),
        )
    }
}
