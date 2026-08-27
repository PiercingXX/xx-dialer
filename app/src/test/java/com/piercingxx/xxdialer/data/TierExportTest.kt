package com.piercingxx.xxdialer.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierExportTest {

    private fun manifest(): String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun `window defaults match the design 09 to 19 everyday window`() {
        assertEquals(9 * 60, TierExport.DEFAULT_START_MINUTE)
        assertEquals(19 * 60, TierExport.DEFAULT_END_MINUTE)
        assertEquals(0b1111111, TierExport.DEFAULT_DAYS_MASK)
        val row = TierExport.windowValues(540, 1140, 127)
        assertEquals(540, row[0])
        assertEquals(1140, row[1])
        assertEquals(127, row[2])
    }

    @Test
    fun `manifest exports the tier provider behind the signature permission`() {
        val xml = manifest()
        assertTrue(xml.contains("android:name=\".data.TierExportProvider\""))
        assertTrue(xml.contains("android:authorities=\"com.piercingxx.xxdialer.tier\""))
        assertTrue(xml.contains("com.piercingxx.xxdialer.permission.TIER_SYNC"))
        val block = xml.substring(xml.indexOf("android:name=\".data.TierExportProvider\""))
        assertTrue(block.contains("android:exported=\"true\""))
        assertTrue(block.contains("android:permission=\"com.piercingxx.xxdialer.permission.TIER_SYNC\""))
    }
}
