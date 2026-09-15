package com.piercingxx.xxdialer.ui

/**
 * A SIP/IMS busy (or congestion) is not a hang-up the user asked for.
 * Telecom delivers [CODE_BUSY] and a supervisory tone, then destroys the
 * Call within a few dozen milliseconds. The in-call surface has to keep a
 * card up and play that tone itself — otherwise the screen vanishes and the
 * audio path dies before anyone can hear "busy".
 *
 * Codes/tones are the public Telecom / ToneGenerator integers so a JVM test
 * can decide without the framework.
 */
object DisconnectNotice {

    const val CODE_BUSY = 7
    const val TONE_BUSY = 17
    const val TONE_CONGESTION = 18

    const val HEADLINE = "Busy"
    const val DETAIL = "Line busy"

    fun shouldHold(code: Int, tone: Int): Boolean =
        code == CODE_BUSY || tone == TONE_BUSY || tone == TONE_CONGESTION

    fun toneToPlay(code: Int, tone: Int): Int = when {
        tone == TONE_BUSY || tone == TONE_CONGESTION -> tone
        code == CODE_BUSY -> TONE_BUSY
        else -> TONE_BUSY
    }

    fun detail(label: String?): String =
        label?.trim()?.takeIf { it.isNotEmpty() } ?: DETAIL
}
