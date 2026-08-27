package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §12 tab visibility. Visual voicemail is OPT-IN and off by default; while
 * the toggle is off the Voicemail tab is ABSENT entirely — gone, not hidden
 * (todo.md: "TabBar omits it when setting is 0"). [TabBar.visibleTabs] is the
 * pure decision seam that encodes this, and TabBar.applyHidden applies it to
 * the view tree on every tab screen's onStart.
 */
class TabBarVisibilityTest {

    @Test
    fun TabVOICEMAILAbsentWhenToggleOff() {
        val visible = TabBar.visibleTabs(
            current = Tab.RULES,
            hiddenNames = emptySet(),
            vvmEnabled = false,
        )
        assertFalse(
            Tab.VOICEMAIL in visible,
            "toggle off ⇒ the Voicemail tab is absent entirely, not merely hidden",
        )
    }

    @Test
    fun `voicemail present only while toggle is on`() {
        val off = TabBar.visibleTabs(Tab.RULES, emptySet(), vvmEnabled = false)
        val on = TabBar.visibleTabs(Tab.RULES, emptySet(), vvmEnabled = true)
        assertFalse(Tab.VOICEMAIL in off)
        assertTrue(Tab.VOICEMAIL in on)
    }

    @Test
    fun `current tab and rules always survive`() {
        val visible = TabBar.visibleTabs(
            current = Tab.KEYPAD,
            hiddenNames = setOf("keypad", "rules"),
            vvmEnabled = true,
        )
        assertTrue(Tab.KEYPAD in visible, "the screen the user is standing on keeps its marker")
        assertTrue(Tab.RULES in visible, "Rules is unhideable so the setting can undo itself")
        assertFalse(Tab.RECENTS in visible)
    }
}