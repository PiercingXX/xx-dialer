package com.piercingxx.xxdialer.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeRampTest {

    @Test
    fun lightGroundDarkensWhiteRampAndDarkGroundRestores() {
        val white90 = 0xE6FFFFFF.toInt()
        val darkened90 = 0xE61A1A1A.toInt()
        assertEquals(darkened90, remapForeground(white90, lightGround = true))
        assertEquals(white90, remapForeground(white90, lightGround = false))
        // Two-way: the original, not the already-darkened colour, is the input
        // on the way back — that is the tag-stash contract.
        assertEquals(white90, remapForeground(white90, lightGround = false))
    }

    @Test
    fun nonWhiteColoursPassThrough() {
        val error = 0xFFFF6767.toInt()
        assertEquals(error, remapForeground(error, lightGround = true))
        assertEquals(error, remapForeground(error, lightGround = false))
        assertNull(darkened(error))
    }
}
