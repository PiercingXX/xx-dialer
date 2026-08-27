package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §12 gated hide-chip on the Rules screen. Visual voicemail is OPT-IN and off
 * by default; while it is off the Voicemail tab is absent entirely — gone, not
 * hidden — so its hide-chip in the "Hide tabs" group must be gone too
 * (todo.md: "Hide-chip for it is gone too"). The gate is
 * [RulesScreenVoicemail.voicemailHideChipVisible], which RulesActivity.syncVoicemailGate
 * applies to the chip's visibility on every load and every toggle.
 */
class VisualVoicemailRulesScreenTest {

    @Test
    fun `hide chip is present while visual voicemail is on`() {
        assertTrue(
            RulesScreenVoicemail.voicemailHideChipVisible(true),
            "toggle on ⇒ the Voicemail tab exists, so its hide-chip must appear",
        )
    }

    @Test
    fun `hide chip is gone while visual voicemail is off`() {
        assertFalse(
            RulesScreenVoicemail.voicemailHideChipVisible(false),
            "toggle off ⇒ the tab is absent, so its hide-chip must be gone (not merely disabled)",
        )
    }
}