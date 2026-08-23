package com.piercingxx.xxphone.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The append-only minting law (§4.3, todo rule #3) — pure JVM. */
class ChannelIdsTest {

    @Test
    fun `fresh purpose mints exactly the section 10 id`() {
        assertEquals("ring_unknown_v1", ChannelIds.versioned(ChannelIds.PURPOSE_RING_UNKNOWN, 1))
        assertEquals(1, ChannelIds.nextVersion(null), "null registry row ⇒ v1, the §10 table id")
    }

    @Test
    fun `successive mints never reuse an id`() {
        var version: Int? = null // registry starts empty (fresh install)
        val seen = mutableSetOf<String>()
        repeat(50) { // simulate 50 tone swaps
            version = ChannelIds.nextVersion(version)
            val id = ChannelIds.versioned(ChannelIds.PURPOSE_RING_UNKNOWN, version!!)
            assertTrue(seen.add(id), "minted id $id was already used — resurrection trap")
        }
        assertEquals(50, seen.size)
    }

    @Test
    fun `next version is monotone from any current`() {
        assertEquals(2, ChannelIds.nextVersion(1))
        assertEquals(8, ChannelIds.nextVersion(7))
    }

    @Test
    fun `custom purposes are namespaced per contact`() {
        assertEquals("custom:abc123", ChannelIds.customPurpose("abc123"))
        assertTrue(ChannelIds.customPurpose("k").startsWith(ChannelIds.CUSTOM_PREFIX))
    }

    @Test
    fun `versionOf round-trips and tolerates junk`() {
        assertEquals(3, ChannelIds.versionOf("ring_unknown_v3"))
        assertEquals(1, ChannelIds.versionOf("ring_default_v1"))
        assertNull(ChannelIds.versionOf("ongoing")) // no separator at all
        assertNull(ChannelIds.versionOf("ring_silent_vX"))
    }

    // --- M4: the registry row can lie; the mint must skip live system ids --------

    @Test
    fun `nextFreeVersion starts one past the registry version`() {
        assertEquals(2, ChannelIds.nextFreeVersion(1) { false })
        assertEquals(1, ChannelIds.nextFreeVersion(null) { false })
    }

    @Test
    fun `crash-orphaned channel id is skipped not resurrected`() {
        // Crash between create(_v2) and the registry write: row says v1, but
        // _v2 is LIVE in the system. Minting must land on _v3, never re-create
        // _v2 (a create-on-existing no-op would freeze tone changes forever).
        assertEquals(3, ChannelIds.nextFreeVersion(1) { it <= 2 })
    }

    @Test
    fun `consecutive orphaned ids walk to the first free slot`() {
        assertEquals(6, ChannelIds.nextFreeVersion(1) { it in 2..5 })
        assertEquals(2, ChannelIds.nextFreeVersion(null) { it == 1 }, "fresh purpose, orphaned v1")
    }

    @Test
    fun `version walk is bounded`() {
        var calls = 0
        val v = ChannelIds.nextFreeVersion(null) { calls++; true } // pathological manager
        assertEquals(ChannelIds.MAX_VERSION_PROBES + 1, v)
    }
}
