package com.piercingxx.xxdialer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hide Voicemail must persist `"voicemail"` in the hidden-tabs set. The Rules
 * listener used to omit it, so the chip looked like it worked until process
 * death restored the fifth tab.
 */
class HiddenTabsPersistenceTest {

    @Test
    fun voicemailIsIncludedInThePersistedSet() {
        assertEquals(
            setOf("voicemail"),
            TabBar.hiddenNames(
                hideRecents = false,
                hideKeypad = false,
                hidePeople = false,
                hideVoicemail = true,
            ),
        )
        assertEquals(
            setOf("recents", "keypad", "people", "voicemail"),
            TabBar.hiddenNames(
                hideRecents = true,
                hideKeypad = true,
                hidePeople = true,
                hideVoicemail = true,
            ),
        )
        assertEquals(
            emptySet(),
            TabBar.hiddenNames(
                hideRecents = false,
                hideKeypad = false,
                hidePeople = false,
                hideVoicemail = false,
            ),
        )
    }

    @Test
    fun rulesListenerPersistsVoicemailThroughHiddenNames() {
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/ui/RulesActivity.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/ui/RulesActivity.kt"),
        ).first { it.exists() }.readText()
        assertTrue(
            src.contains("hideVoicemail = binding.chipTabVoicemail.isChecked"),
            "Rules must persist the Voicemail hide-chip, not drop it from the set",
        )
        assertTrue(src.contains("TabBar.hiddenNames("))
    }

    @Test
    fun applyHiddenGonesTheHairlineWithTheLabel() {
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/ui/TabBar.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/ui/TabBar.kt"),
        ).first { it.exists() }.readText()
        val apply = src.substringAfter("fun applyHidden").substringBefore("private fun View.isVisibleWhen")
        assertTrue(
            apply.contains("tab.indicatorId"),
            "applyHidden must GONE tab_ind_* with the label or VVM-off leaves a ghost slot",
        )
        assertTrue(apply.contains("View.GONE"))
    }
}
