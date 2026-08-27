package com.piercingxx.xxdialer.vvm

/**
 * Pure fetch-vs-play decision seam (todo.md VVM audio, T1). A voicemail that
 * already carries audio content can be played directly; one that does not
 * (VoicemailContract HASCONTENT == 0) must first be fetched from the carrier by
 * broadcasting ACTION_FETCH_VOICEMAIL. This object owns that single rule so the
 * fetch/play branching in [VvmAudioPlayer] stays a one-line decision and is
 * trivially testable without any Android framework.
 */
object VvmAudioFetchPolicy {

    /**
     * True when the voicemail must be fetched before it can be played — i.e.
     * when [hasContent] is false. A voicemail that already has content is played
     * directly and must never be re-fetched.
     */
    fun shouldFetch(hasContent: Boolean): Boolean = !hasContent
}