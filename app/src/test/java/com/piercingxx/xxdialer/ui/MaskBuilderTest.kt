package com.piercingxx.xxdialer.ui

import com.piercingxx.xxdialer.core.PatternRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mask builder seam behind the Rules dialog (§8): typed digits →
 * (prefix, wildcards) honoring core.PatternRule's contract — prefix excludes
 * '+', digits only, LENGTH-EXACT matching.
 */
class MaskBuilderTest {

    // --- digitsOf -----------------------------------------------------------------

    @Test
    fun `digits strip formatting and separators`() {
        assertEquals("14255550134", MaskBuilder.digitsOf("+1 (425) 555-0134"))
        assertEquals("4255550134", MaskBuilder.digitsOf("425.555.0134"))
        assertEquals("", MaskBuilder.digitsOf("no digits here"))
    }

    // --- build ----------------------------------------------------------------------

    @Test
    fun `build masks exactly the requested tail`() {
        val draft = MaskBuilder.build("14255550134", 5)
        assertEquals("142555", draft!!.prefix)
        assertEquals(5, draft.wildcards)
    }

    @Test
    fun `build keeps at least one prefix digit`() {
        assertNull(MaskBuilder.build("425", 3), "masking everything leaves no prefix")
        assertNull(MaskBuilder.build("425", 4), "masking past the end is invalid")
        assertNull(MaskBuilder.build("", 1))
    }

    @Test
    fun `build rejects nondigit prefixes`() {
        assertNull(MaskBuilder.build("+1425555", 2), "a stray '+' must not survive into a prefix")
    }

    @Test
    fun `zero-mask build is a legal exact-number rule`() {
        val draft = MaskBuilder.build("14255550134", 0)
        assertEquals("14255550134", draft!!.prefix)
        assertEquals(0, draft.wildcards)
    }

    @Test
    fun `drafts satisfy core PatternRule length-exact matching`() {
        val rule = PatternRule("142555", 5) // from MaskBuilder.build("14255550134", 5)
        assertTrue(rule.matches("+14255550134"))
        assertTrue(rule.matches("14255550134"), "matches() strips '+' itself")
        assertFalse(rule.matches("+1425555013"), "shorter: length-exact refuses")
        assertFalse(rule.matches("+142555501349"), "longer: must not swallow")
        assertFalse(rule.matches("+14255650134"), "different exchange: prefix mismatch")
    }

    // --- preview --------------------------------------------------------------------

    @Test
    fun `preview shapes NANP eleven-digit E164 with country code`() {
        val draft = MaskBuilder.build("14255550134", 4)!!
        assertEquals("+1 425-555-XXXX", MaskBuilder.preview(draft))
    }

    @Test
    fun `preview shapes ten-digit NANP without country code`() {
        val draft = MaskBuilder.build("4255550134", 4)!!
        assertEquals("425-555-XXXX", MaskBuilder.preview(draft))
    }

    @Test
    fun `preview falls back to plus-digits for other lengths`() {
        assertEquals("+44XXXXXXX", MaskBuilder.preview(MaskBuilder.Draft("44", 7)))
        assertEquals("+1X", MaskBuilder.preview(MaskBuilder.Draft("1", 1)))
    }

    // --- neighbor-spoof preset ---------------------------------------------------------

    @Test
    fun `neighbor draft takes six E164 digits, tail wildcards`() {
        // Own line +1 425 555 0134 → full E164 digits 14255550134 (country code
        // KEPT — length-exact matching runs on exactly these digits).
        val draft = MaskBuilder.neighborDraft("14255550134")!!
        assertEquals("142555", draft.prefix)
        assertEquals(5, draft.wildcards)
        assertTrue(PatternRule("142555", 5).matches("+14255550134"))
        assertFalse(
            PatternRule("142555", 5).matches("+14255650134"),
            "a different exchange is not a neighbor",
        )
    }

    @Test
    fun `neighbor draft handles non-NANP and short lines`() {
        val draft = MaskBuilder.neighborDraft("442055123456")!!
        assertEquals("442055", draft.prefix)
        assertEquals(6, draft.wildcards)
        assertNull(MaskBuilder.neighborDraft("456789"), "six digits leave no wildcard tail")
        assertNull(MaskBuilder.neighborDraft(""))
        assertNull(MaskBuilder.neighborDraft("+142555"))
    }
}
