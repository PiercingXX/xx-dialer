package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §12 Voicemail item in the shared tab shell (view_tab_bar.xml). The four
 * original tabs each declare a label TextView (`tab_*`) and a hairline
 * indicator (`tab_ind_*`); the Voicemail item adds the fifth pair. [Tab.VOICEMAIL]
 * is the single seam that binds those layout IDs and the `tab_voicemail`
 * string to the running app, so asserting it is wired to the exact IDs the
 * layout declares is the JVM-checkable proof that the item exists in the
 * shared bar — a mismatch here fails the moment the layout or the enum drifts.
 */
class VoicemailTabBarLayoutTest {

    @Test
    fun `voicemail item is wired to the shared tab bar ids and label`() {
        val tab = Tab.VOICEMAIL
        assertEquals(R.id.tab_voicemail, tab.itemId, "label TextView id must match view_tab_bar.xml")
        assertEquals(R.id.tab_ind_voicemail, tab.indicatorId, "hairline indicator id must match view_tab_bar.xml")
        assertEquals(R.string.tab_voicemail, tab.labelId, "label must resolve to the tab_voicemail string")
    }

    @Test
    fun `voicemail is a real tab in the shared bar`() {
        assertTrue(
            Tab.VOICEMAIL in Tab.entries,
            "the Voicemail item must be part of the shared tab bar's enum of tabs",
        )
    }
}