package com.piercingxx.xxphone.core

enum class Tone { DEFAULT, UNKNOWN }

/**
 * Every disposition the screening log (R7) can print. [uiLabel] carries the
 * canonical design.md wording; where the printed string embeds dynamic data
 * (window bounds, pattern mask, weekday of the last outgoing call) the label
 * is the static skeleton and the caller substitutes the live value.
 */
enum class Reason(val uiLabel: String) {
    EMERGENCY_CALLBACK("emergency callback"),
    SEND_TO_VOICEMAIL("send to voicemail"),
    USER_BLOCKED("blocked"),
    PATTERN_BLOCKED("blocked by pattern"),
    STIR_FAILED("STIR failed"),
    STARRED("starred"),
    BUSINESS_IN_WINDOW("business"),
    BUSINESS_OUTSIDE_WINDOW("Business, outside 09–19"),
    SAVED("saved contact"),
    RECENT_OUTGOING("you called them Tue"),
    PATTERN_SILENCED("silenced by pattern"),
    UNKNOWN_IN_WINDOW("unknown caller"),
    UNKNOWN_OUTSIDE_WINDOW("Unknown, outside 09–17"),
    HIDDEN_POLICY("hidden caller"),
    REPEAT_CALLER("repeat caller"),
    EXPECTING_A_CALL("rang · expecting a call"),
}

sealed interface Verdict {
    data object Block : Verdict
    data class Ring(val tone: Tone) : Verdict
    data class Silence(val reason: Reason) : Verdict
}

data class CallerFacts(
    val number: String?,          // E.164-normalized; null = withheld/hidden
    val saved: Boolean,
    val starred: Boolean,
    val bizTier: Boolean,
    val sendToVoicemail: Boolean,
    val userBlocked: Boolean,     // system blocklist never reaches here
    val stirFailed: Boolean,
    val repeatCaller: Boolean,
    val recentOutgoing: Boolean,
    val cnapName: String?,        // context only — NEVER changes a verdict
    val emergencyWindow: Boolean,
)

/**
 * Observe mode is a gate, not a branch (todo rule #6): enforcement lives in
 * the callers, which consult this after decide() has produced its verdict.
 * The core ships it only so callers share one vocabulary — it must never
 * influence a verdict.
 */
enum class Mode { ENFORCING, OBSERVING }
