package com.piercingxx.xxdialer.core

/**
 * How many times the ringtone plays before the phone goes quiet.
 *
 * The platform CallStyle channel plays a tone once and then stops — that is
 * the observed default, and [ONCE] keeps it. [TWICE] and [UNTIL_VOICEMAIL]
 * are opt-in repeats of the same tone, still on the ringer stream.
 */
enum class RingRepeat {
    ONCE,
    TWICE,
    UNTIL_VOICEMAIL,
}

object RingRepeatPolicy {

    const val TOKEN_ONCE = "once"
    const val TOKEN_TWICE = "twice"
    const val TOKEN_UNTIL_VOICEMAIL = "until_vm"

    val DEFAULT = RingRepeat.ONCE

    /** Garbage or absence reads as [ONCE] — the shipped behaviour. */
    fun parse(raw: String?): RingRepeat = when (raw?.trim()?.lowercase()) {
        TOKEN_TWICE -> RingRepeat.TWICE
        TOKEN_UNTIL_VOICEMAIL, "until_voicemail" -> RingRepeat.UNTIL_VOICEMAIL
        else -> RingRepeat.ONCE
    }

    fun token(repeat: RingRepeat): String = when (repeat) {
        RingRepeat.ONCE -> TOKEN_ONCE
        RingRepeat.TWICE -> TOKEN_TWICE
        RingRepeat.UNTIL_VOICEMAIL -> TOKEN_UNTIL_VOICEMAIL
    }

    /**
     * Whether the CallStyle card should keep the channel looping until we
     * cancel it. Once-only rides the platform default (no insistent flag).
     */
    fun insistent(repeat: RingRepeat): Boolean = repeat != RingRepeat.ONCE

    /**
     * How long the tone may keep playing before we downgrade to silent.
     * Null means "leave it looping until the call leaves RINGING".
     */
    fun silenceAfterMs(repeat: RingRepeat, toneDurationMs: Long): Long? {
        val duration = toneDurationMs.coerceAtLeast(1L)
        return when (repeat) {
            RingRepeat.ONCE -> duration
            RingRepeat.TWICE -> duration * 2
            RingRepeat.UNTIL_VOICEMAIL -> null
        }
    }
}
