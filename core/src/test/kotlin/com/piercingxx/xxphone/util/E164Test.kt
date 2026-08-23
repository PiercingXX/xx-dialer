package com.piercingxx.xxphone.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §16 E164 contract: `+1` forms, national forms, `00` prefixes, short codes,
 * garbage — all against the "one caller, one identity" property.
 */
class E164Test {

    private val canonical = "+14155550100"

    @Test
    fun plusForm_isCanonical() {
        assertEquals(canonical, E164.normalize("+14155550100"))
    }

    @Test
    fun nationalDigits_resolveViaDefaultRegion() {
        assertEquals(canonical, E164.normalize("4155550100"))
    }

    @Test
    fun formattedPunctuation_normalizesToSameIdentity() {
        assertEquals(canonical, E164.normalize("(415) 555-0100"))
        assertEquals(canonical, E164.normalize("415.555.0100"))
    }

    @Test
    fun doubleZeroInternationalPrefix_liftedToPlus() {
        assertEquals(canonical, E164.normalize("0014155550100"))
    }

    @Test
    fun oneCallerOneIdentity_everyWrittenForm_collapsesToOneE164() {
        val sameCaller = listOf(
            "+14155550100",
            "+1 415-555-0100",
            "4155550100",
            "(415) 555-0100",
            "415.555.0100",
            "0014155550100",
            "001 415 555 0100",
        )
        for (form in sameCaller) {
            assertEquals(canonical, E164.normalize(form), "identity broke for: $form")
        }
    }

    @Test
    fun shortCodes_areGarbage_null() {
        assertNull(E164.normalize("911"))
        assertNull(E164.normalize("40404"))
    }

    @Test
    fun garbage_isNull_neverLossy() {
        assertNull(E164.normalize(null))
        assertNull(E164.normalize(""))
        assertNull(E164.normalize("   "))
        assertNull(E164.normalize("not-a-number"))
        assertNull(E164.normalize("+"))
        assertNull(E164.normalize("123456789012345678901234567890123"))
    }
}
