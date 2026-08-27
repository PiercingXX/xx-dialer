package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure fetch-vs-play decision seam (todo.md VVM audio, T1). A voicemail is
 * fetched only when it carries no audio content (HASCONTENT == 0); one that
 * already has content is played directly.
 */
class VvmAudioFetchPolicyTest {

    @Test
    fun fetchOnlyWhenNoContent() {
        assertTrue(
            VvmAudioFetchPolicy.shouldFetch(hasContent = false),
            "a voicemail without audio content must be fetched before playback",
        )
        assertFalse(
            VvmAudioFetchPolicy.shouldFetch(hasContent = true),
            "a voicemail that already has content must be played directly, never fetched",
        )
    }
}