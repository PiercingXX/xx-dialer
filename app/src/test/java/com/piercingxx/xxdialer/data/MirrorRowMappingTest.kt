package com.piercingxx.xxdialer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E164-driven mirror-row mapping (§6/§9), pure JVM — the provider cursor is
 * reduced to nullable scalars in [MirrorRows], so the identity rules are
 * provable without a device.
 */
class MirrorRowMappingTest {

    @Test
    fun `national form normalizes to one e164 identity`() {
        val row = MirrorRows.row(
            lookupKey = "abc123/",
            rawNumber = "(415) 555-0100",
            displayName = "Ada Lovelace",
            starred = true,
            customRingtone = "content://media/ringtone/1",
            sendToVoicemail = false,
            refreshedAt = 42L,
        )
        assertEquals("+14155550100", row!!.e164)
        assertEquals("abc123/", row.lookupKey)
        assertEquals("Ada Lovelace", row.displayName)
        assertTrue(row.starred)
        assertEquals("content://media/ringtone/1", row.customRingtone)
        assertFalse(row.sendToVoicemail)
        assertEquals(42L, row.refreshedAt)
    }

    @Test
    fun `garbage number has no identity and yields no row`() {
        val row = MirrorRows.row(
            lookupKey = "abc123/",
            rawNumber = "not-a-number",
            displayName = "Ghost",
            starred = false,
            customRingtone = null,
            sendToVoicemail = true,
            refreshedAt = 1L,
        )
        assertNull(row) // unmatchable rows would be dead weight; skip (§6)
    }

    @Test
    fun `blank or null lookup key yields no row even with valid number`() {
        assertNull(
            MirrorRows.row("", "+14155550100", "A", false, null, false, 0L),
        )
        assertNull(
            MirrorRows.row(null, "+14155550100", "A", false, null, false, 0L),
        )
    }

    @Test
    fun `null display name degrades to empty string never a crash`() {
        val row = MirrorRows.row("k/", "+14155550100", null, false, null, false, 7L)
        assertEquals("", row!!.displayName)
    }

    @Test
    fun `saved-ness is presence itself and bizTier starts unresolved`() {
        val row = MirrorRows.liveRow(
            lookupKey = "k/",
            e164 = "+14155550100",
            displayName = "Ada",
            starred = false,
            customRingtone = null,
            sendToVoicemail = false,
            refreshedAt = 9L,
        )
        assertTrue(row!!.saved) // §9: a mirror row exists ⇒ the caller is saved
        assertFalse(row.bizTier) // resolved only by the DAO JOIN at query time
    }

    @Test
    fun `dedupe keeps first resolvable number per contact`() {
        val rows = sequenceOf(
            MirrorRows.row("k1/", "4155550100", "A", false, null, false, 0L)!!,
            MirrorRows.row("k1/", "+14155550999", "A", false, null, false, 0L)!!,
            MirrorRows.row("k2/", "0014155550100", "B", false, null, false, 0L)!!,
        )
        val deduped = MirrorRows.dedupe(rows)
        assertEquals(2, deduped.size)
        assertEquals("+14155550100", deduped[0].e164) // same caller as k2's 00-form (§6 property)
    }
}
