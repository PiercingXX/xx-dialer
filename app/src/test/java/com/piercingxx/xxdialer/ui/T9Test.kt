package com.piercingxx.xxdialer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keypad match-as-you-type (§12.2) and monogram initials (§12.1) — pure JVM. */
class T9Test {

    // ---- encode -------------------------------------------------------------

    @Test
    fun letters_map_onto_keypad_digits() {
        assertEquals("22233344455566677778889999", T9.encode("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun case_is_irrelevant_and_digits_pass_through() {
        // GHI-jkl -> 444-555, then the literal digits pass through untouched.
        assertEquals("4445554255550", T9.encode("GHI-jkl 425-555-0"))
    }

    @Test
    fun punctuation_drops_out() {
        assertEquals("12", T9.encode("+1 (2)"))
    }

    @Test
    fun digits_of_keeps_only_digits() {
        assertEquals("14155550100", T9.digitsOf("+1 (415) 555-0100"))
    }

    // ---- hit ----------------------------------------------------------------

    @Test
    fun query_matching_number_prefix_hits_in_number() {
        val hit = T9.hit("425", "+14255550134", "Dr. Reyes")
        val inNumber = assertIs<T9.Hit.InNumber>(hit)
        assertEquals(1, inNumber.digitStart) // after the country-code '1'
        assertEquals(3, inNumber.len)
    }

    @Test
    fun query_inside_the_number_hits_anywhere() {
        val hit = T9.hit("0134", "+14255550134", "Dr. Reyes")
        val inNumber = assertIs<T9.Hit.InNumber>(hit)
        assertEquals(7, inNumber.digitStart)
        assertEquals(4, inNumber.len)
    }

    @Test
    fun name_letters_translate_to_t9_and_match_as_prefix() {
        val hit = T9.hit("37", "2065550177", "Dr. Reyes") // D=3, R=7
        val inName = assertIs<T9.Hit.InName>(hit)
        assertEquals(2, inName.prefixLen)
    }

    @Test
    fun name_match_requires_prefix_not_infix() {
        // "555" occurs nowhere in this number and MARGARET's T9 starts 627…,
        // so neither channel may claim it.
        assertNull(T9.hit("555", "+14150000000", "Margaret Adams"))
        // A name infix (GAR = 427) is not a match either — names are prefix-only.
        assertNull(T9.hit("427", "+14150000000", "Margaret Adams"))
    }

    @Test
    fun empty_or_digitless_queries_never_hit() {
        assertNull(T9.hit("", "+14155550100", "Jal"))
        assertNull(T9.hit("*#", "+14155550100", "Jal"))
    }

    @Test
    fun number_wins_when_both_could_match() {
        // 425 is inside the number AND M-A-R would need 627 — but "REY"=739 vs 425:
        // construct a name whose T9 starts with the same digits as a number hit.
        val hit = T9.hit("426", "+14265550100", "Han Solo") // HAN = 426
        assertIs<T9.Hit.InNumber>(hit)
    }

    // ---- charRange ----------------------------------------------------------

    @Test
    fun char_range_maps_digits_back_onto_formatted_numbers() {
        val raw = "+1 (425) 555-0100" // digit '1'→i1, '4'→i4, '2'→i5, '5'→i6 …
        val range = T9.charRange(raw, T9.Hit.InNumber(digitStart = 1, len = 3))
        assertEquals(4..6, range)
        assertEquals("425", raw.substring(4..6))
    }

    @Test
    fun char_range_survives_a_leading_plus_only_shape() {
        val range = T9.charRange("+14155550100", T9.Hit.InNumber(0, 4))
        assertEquals(1..4, range)
    }

    @Test
    fun char_range_returns_null_when_raw_shrunk_below_the_hit() {
        assertNull(T9.charRange("+1", T9.Hit.InNumber(3, 2)))
    }

    // ---- monograms ----------------------------------------------------------

    @Test
    fun initials_take_two_words() {
        assertEquals("MA", Monograms.initials("Margaret Adams"))
    }

    @Test
    fun single_word_keeps_its_first_two_letters() {
        assertEquals("RO", Monograms.initials("Roscoe"))
    }

    @Test
    fun non_letter_words_are_skipped() {
        assertEquals("MA", Monograms.initials("2nd Margaret Adams"))
    }

    @Test
    fun blank_names_degrade_to_hash() {
        assertEquals("#", Monograms.initials(null))
        assertEquals("#", Monograms.initials("   "))
    }
}
