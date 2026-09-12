package com.piercingxx.xxdialer.ring

/**
 * When the incoming card may make sound, and when we may self-play a
 * repeating ringtone instead of the CallStyle channel.
 *
 * Starred contacts (and emergency callbacks) pierce a platform silent-ring
 * request so Nope-Mode's "starred still rings" contract holds even when
 * Telecom stamps `EXTRA_SILENT_RINGING_REQUESTED` for every call under DND.
 *
 * Self-played [android.media.Ringtone] audio is muted globally by DND and
 * cannot honor the starred-contacts exception — repeating rings stay on the
 * notification channel whenever an interruption filter is active.
 */
object RingTonePlayback {

    fun allowTone(
        silentRequested: Boolean,
        starred: Boolean,
        emergencyWindow: Boolean,
    ): Boolean = !silentRequested || starred || emergencyWindow

    fun selfPlay(
        insistent: Boolean,
        waitingOverActive: Boolean,
        dndFiltering: Boolean,
    ): Boolean = insistent && !waitingOverActive && !dndFiltering
}
