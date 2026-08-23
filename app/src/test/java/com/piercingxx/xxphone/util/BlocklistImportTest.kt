package com.piercingxx.xxphone.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §8 offline blocklist import: messy real-world files, parsed entirely on the
 * JVM (R8). One number per line; `#` comments and blanks are structure;
 * garbage is counted, never fatal.
 */
class BlocklistImportTest {

    @Test
    fun `mixed file yields survivors and counts garbage`() {
        val text = """
            # exported blocklist
            +1 415 555 0100

            4155550101
            not-a-phone
            12345
            # mid-file comment
            0044 20 7946 0958
        """.trimIndent()
        val parsed = BlocklistImport.parse(text)
        assertEquals(
            listOf("+14155550100", "+14155550101", "+442079460958"),
            parsed.numbers,
        )
        assertEquals(2, parsed.skipped) // "not-a-phone" and "12345"
    }

    @Test
    fun `blank lines and comments are structure not garbage`() {
        val parsed = BlocklistImport.parse("\n\n# header\n   \n# footer\n")
        assertEquals(emptyList(), parsed.numbers)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `empty file imports nothing skips nothing`() {
        val parsed = BlocklistImport.parse("")
        assertEquals(0, parsed.numbers.size + parsed.skipped)
    }

    @Test
    fun `whitespace around a line does not disqualify it`() {
        val parsed = BlocklistImport.parse("  +14155550100  ")
        assertEquals(listOf("+14155550100"), parsed.numbers)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `duplicates are kept for the insert layer to reconcile`() {
        val parsed = BlocklistImport.parse("+14155550100\n+1 (415) 555-0100")
        assertEquals(listOf("+14155550100", "+14155550100"), parsed.numbers)
    }
}
