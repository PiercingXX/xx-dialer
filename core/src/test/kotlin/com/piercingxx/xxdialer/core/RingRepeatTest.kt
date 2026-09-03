package com.piercingxx.xxdialer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How many times the ringtone plays — parse, tokens, silence clock. */
class RingRepeatTest {

    @Test
    fun parse_is_case_insensitive_and_fails_open_to_once() {
        assertEquals(RingRepeat.ONCE, RingRepeatPolicy.parse("once"))
        assertEquals(RingRepeat.TWICE, RingRepeatPolicy.parse("TWICE"))
        assertEquals(RingRepeat.UNTIL_VOICEMAIL, RingRepeatPolicy.parse(" until_vm "))
        assertEquals(RingRepeat.UNTIL_VOICEMAIL, RingRepeatPolicy.parse("until_voicemail"))
        assertEquals(RingRepeat.ONCE, RingRepeatPolicy.parse(null))
        assertEquals(RingRepeat.ONCE, RingRepeatPolicy.parse("forever"))
        assertEquals(RingRepeat.ONCE, RingRepeatPolicy.parse(""))
    }

    @Test
    fun token_round_trips_every_value() {
        RingRepeat.entries.forEach { value ->
            assertEquals(value, RingRepeatPolicy.parse(RingRepeatPolicy.token(value)))
        }
    }

    @Test
    fun once_is_not_insistent_the_others_are() {
        assertFalse(RingRepeatPolicy.insistent(RingRepeat.ONCE))
        assertTrue(RingRepeatPolicy.insistent(RingRepeat.TWICE))
        assertTrue(RingRepeatPolicy.insistent(RingRepeat.UNTIL_VOICEMAIL))
    }

    @Test
    fun silence_clock_is_one_or_two_plays_or_open_ended() {
        assertEquals(5_000L, RingRepeatPolicy.silenceAfterMs(RingRepeat.ONCE, 5_000L))
        assertEquals(10_000L, RingRepeatPolicy.silenceAfterMs(RingRepeat.TWICE, 5_000L))
        assertNull(RingRepeatPolicy.silenceAfterMs(RingRepeat.UNTIL_VOICEMAIL, 5_000L))
    }

    @Test
    fun zero_or_negative_tone_duration_still_produces_a_positive_clock() {
        assertEquals(1L, RingRepeatPolicy.silenceAfterMs(RingRepeat.ONCE, 0L))
        assertEquals(2L, RingRepeatPolicy.silenceAfterMs(RingRepeat.TWICE, -12L))
    }
}
