package com.piercingxx.xxdialer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Recents filter chips sit in a ChipGroup with singleSelection +
 * selectionRequired and a setOnCheckedStateChangeListener. Widget.Xx.Chip
 * parents Widget.MaterialComponents.Chip.Action, which is NOT checkable, so
 * without android:checkable="true" taps never fire the listener and the
 * filter stays ALL.
 */
class RecentsChipCheckableTest {

    private val layout: String =
        sequenceOf(
            File("src/main/res/layout/activity_recents.xml"),
            File("app/src/main/res/layout/activity_recents.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun recentsFilterChipsAreCheckable() {
        for (id in listOf(
            "recents_chip_all",
            "recents_chip_missed",
            "recents_chip_silenced",
            "recents_chip_blocked",
        )) {
            val block = chipBlock(layout, id)
            assertTrue(
                block.contains("android:checkable=\"true\""),
                "$id must be checkable so ChipGroup selection actually changes the filter",
            )
        }
    }

    @Test
    fun recentsChipGroupIsSingleExclusiveSelection() {
        assertTrue(layout.contains("app:singleSelection=\"true\""))
        assertTrue(layout.contains("app:selectionRequired=\"true\""))
    }

    private fun chipBlock(xml: String, id: String): String {
        val idAttr = "android:id=\"@+id/$id\""
        val start = xml.indexOf(idAttr)
        require(start >= 0) { "missing $id in activity_recents.xml" }
        val tagStart = xml.lastIndexOf("<com.google.android.material.chip.Chip", start)
        val tagEnd = xml.indexOf("/>", start).let { if (it < 0) xml.indexOf("</com.google.android.material.chip.Chip>", start) else it }
        return xml.substring(tagStart, tagEnd + 2)
    }
}
