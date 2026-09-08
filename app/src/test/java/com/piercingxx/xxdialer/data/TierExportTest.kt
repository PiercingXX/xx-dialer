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
    fun `biz write contract is lookup key only`() {
        assertEquals("lookup_key = ?", TierExport.BIZ_DELETE_SELECTION)
        assertEquals(mapOf("lookup_key" to "abc123/"), TierExport.bizInsertPairs("abc123/"))
        assertEquals(arrayOf("abc123/").toList(), TierExport.bizDeleteArgs("abc123/").toList())
    }

    @Test
    fun `custom group names are reserved-checked`() {
        assertTrue(TierExport.isReservedGroup("star"))
        assertTrue(TierExport.isReservedGroup("Business"))
        assertTrue(TierExport.isReservedGroup("biz"))
        assertEquals("Family", TierExport.groupTier(" Family "))
        assertEquals("Blocked", TierExport.groupTier("blocked"))
        assertEquals("Blocked", TierExport.groupTier("Block"))
        assertEquals(null, TierExport.groupTier("star"))
    }

    @Test
    fun `provider writes biz membership instead of no-op stubs`() {
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/data/TierExportProvider.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/data/TierExportProvider.kt"),
        ).first { it.exists() }.readText()
        assertTrue(src.contains(".upsert("))
        assertTrue(src.contains(".delete(key, BackupJson.TIER_BIZ)"))
        assertTrue(src.contains("BackupJson.TIER_BIZ"))
        assertTrue(src.contains("PATH_GROUPS"))
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
