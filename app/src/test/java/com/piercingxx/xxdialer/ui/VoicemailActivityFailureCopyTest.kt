package com.piercingxx.xxdialer.ui

import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.vvm.VvmDetailPlayer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2 — the voicemail detail screen surfaces honest copy when a detail action
 * (play / speaker / call back / delete) fails. Pins that a failing
 * [VvmDetailPlayer] drives the activity to show the matching failure message
 * instead of a silent no-op, for every one of the four detail actions. The
 * failing player exercises the real activity seams ([VoicemailActivity.toggleDetailPlay],
 * [VoicemailActivity.setDetailSpeakerphone], [VoicemailActivity.callBackVoicemail],
 * [VoicemailActivity.deleteVoicemail]) so the copy is reachable from the running
 * app, not just from a method that happens to exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicemailActivityFailureCopyTest {

    @Test
    fun detailFailuresShowCopy() {
        val activity = Robolectric.buildActivity(VoicemailActivity::class.java).setup().get()
        activity.attachDetailPlayer(failingPlayer())
        val failure = activity.findViewById<TextView>(R.id.vvm_detail_failure)
        val uri = Uri.parse("content://com.piercingxx.xxdialer/voicemail/1")

        // Play fails -> play copy.
        activity.toggleDetailPlay(uri)
        assertEquals(
            "a failed play must surface the play failure copy",
            activity.getString(R.string.vvm_detail_play_failed),
            failure.text.toString(),
        )
        assertEquals("the failure copy must be visible", View.VISIBLE, failure.visibility)

        // Speaker fails -> speaker copy.
        activity.setDetailSpeakerphone(true)
        assertEquals(
            "a failed speaker switch must surface the speaker failure copy",
            activity.getString(R.string.vvm_detail_speaker_failed),
            failure.text.toString(),
        )

        // Call back fails -> call-back copy.
        activity.callBackVoicemail("+15551234567")
        assertEquals(
            "a failed call back must surface the call-back failure copy",
            activity.getString(R.string.vvm_detail_callback_failed),
            failure.text.toString(),
        )

        // Delete fails -> delete copy.
        activity.deleteVoicemail(uri)
        assertEquals(
            "a failed delete must surface the delete failure copy",
            activity.getString(R.string.vvm_detail_delete_failed),
            failure.text.toString(),
        )
    }

    /** A [VvmDetailPlayer] whose every detail seam fails, so each action returns false. */
    private fun failingPlayer(): VvmDetailPlayer = VvmDetailPlayer(
        context = ApplicationProvider.getApplicationContext(),
        speaker = { false },
        dial = { false },
        deleteRow = { false },
    )
}