package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupGlyphsTest {

    @Test
    fun codepointsAreTheNerdFontAwesomeMarks() {
        assertEquals("\uF005", GroupGlyphs.STAR)
        assertEquals("\uF0B1", GroupGlyphs.BUSINESS)
        assertEquals("\uF0C0", GroupGlyphs.FAMILY)
        assertEquals("\uF05E", GroupGlyphs.BLOCK)
    }

    @Test
    fun reservedNamesMapCaseInsensitively() {
        assertEquals(GroupGlyphs.STAR, GroupGlyphs.ofName("star"))
        assertEquals(GroupGlyphs.STAR, GroupGlyphs.ofName("  Starred "))
        assertEquals(GroupGlyphs.BUSINESS, GroupGlyphs.ofName("Business"))
        assertEquals(GroupGlyphs.BUSINESS, GroupGlyphs.ofName("biz"))
        assertEquals(GroupGlyphs.FAMILY, GroupGlyphs.ofName("Family"))
        assertEquals(GroupGlyphs.BLOCK, GroupGlyphs.ofName("blocked"))
        assertEquals(GroupGlyphs.BLOCK, GroupGlyphs.ofName("Block"))
        assertNull(GroupGlyphs.ofName("Gym"))
    }

    @Test
    fun prefixLeavesUnknownNamesAlone() {
        assertEquals("${GroupGlyphs.FAMILY} Family", GroupGlyphs.prefix("Family"))
        assertEquals("Gym", GroupGlyphs.prefix("Gym"))
    }

    @Test
    fun marksJoinInStarBusinessFamilyBlockOrder() {
        assertEquals("", GroupGlyphs.marks(false, false, false, false))
        assertEquals(GroupGlyphs.STAR, GroupGlyphs.marks(true, false, false, false))
        assertEquals(
            "${GroupGlyphs.STAR} ${GroupGlyphs.BUSINESS} ${GroupGlyphs.FAMILY} ${GroupGlyphs.BLOCK}",
            GroupGlyphs.marks(true, true, true, true),
        )
        assertEquals(
            "${GroupGlyphs.BUSINESS} ${GroupGlyphs.BLOCK}",
            GroupGlyphs.marks(false, true, false, true),
        )
    }
}
