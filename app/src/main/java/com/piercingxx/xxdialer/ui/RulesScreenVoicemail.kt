package com.piercingxx.xxdialer.ui

/**
 * Rules-screen policy for the opt-in Visual voicemail (§12). One toggle, two
 * effects (todo.md D2): on shows the Voicemail tab AND starts the client; off
 * the tab is absent entirely — gone, not hidden. This object encodes the
 * consequence the Rules screen must render: the Voicemail hide-chip in the
 * "Hide tabs" group is gated on the toggle. While it is off the tab does not
 * exist, so its hide-chip must be gone too — not merely disabled (todo.md:
 * "Hide-chip for it is gone too").
 */
object RulesScreenVoicemail {

    /**
     * Whether the Voicemail hide-chip should be present on the Rules screen.
     * Gated on the toggle: off ⇒ the tab (and its hide-chip) is gone, so a
     * stale "voicemail" entry in the hidden set is inert and the chip hides.
     */
    fun voicemailHideChipVisible(vvmEnabled: Boolean): Boolean = vvmEnabled
}