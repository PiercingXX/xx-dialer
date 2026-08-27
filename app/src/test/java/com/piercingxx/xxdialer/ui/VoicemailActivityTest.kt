package com.piercingxx.xxdialer.ui

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.piercingxx.xxdialer.R
import com.piercingxx.xxdialer.vvm.VvmListRow
import com.piercingxx.xxdialer.vvm.VvmListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * T4 — the list and detail Views wired into [VoicemailActivity], rendering every
 * honest [VvmListState]. Pins that:
 * - the list RecyclerView is present and carries an adapter (list wired);
 * - every honest state renders: the explanatory message shows and the list hides
 *   for every non-list state, and only the list state shows rows;
 * - renderRows maps an empty mailbox to the honest Empty state and a non-empty
 *   one to rows in the list;
 * - the detail controls live in the row layout and the activity's detail seams
 *   route to the [VvmDetailPlayer] (play/speaker observable under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicemailActivityTest {

    @Test
    fun listAndDetailWired() {
        val activity = Robolectric.buildActivity(VoicemailActivity::class.java).setup().get()

        // --- List wired: the RecyclerView exists and carries an adapter. ---
        val list = activity.findViewById<RecyclerView>(R.id.vvm_list)
        assertNotNull("the voicemail list RecyclerView must be present", list)
        assertNotNull("the voicemail list must carry an adapter", list.adapter)

        // --- Every honest state renders: message + list visibility. ---
        val emptyState = activity.findViewById<TextView>(R.id.vvm_empty_state)
        fun assertState(state: VvmListState, messageRes: Int?) {
            activity.renderState(state)
            if (messageRes != null) {
                assertEquals(
                    "empty-state text must match the honest state",
                    activity.getString(messageRes),
                    emptyState.text.toString(),
                )
                assertEquals("empty-state must be visible", View.VISIBLE, emptyState.visibility)
            } else {
                assertEquals("empty-state must be gone for the list", View.GONE, emptyState.visibility)
            }
            assertEquals(
                "list visibility must follow the state",
                if (state is VvmListState.List) View.VISIBLE else View.GONE,
                list.visibility,
            )
        }
        assertState(VvmListState.NoCarrierConfig, R.string.vvm_state_no_config)
        assertState(VvmListState.Activating, R.string.vvm_state_activating)
        assertState(VvmListState.NetworkRevoked, R.string.vvm_state_network_revoked)
        assertState(VvmListState.ImapError, R.string.vvm_state_imap_error)
        assertState(VvmListState.Empty, R.string.vvm_state_empty)
        assertState(VvmListState.List(2), null)

        // --- renderRows: empty → Empty, non-empty → rows in the list. ---
        activity.renderRows(emptyList())
        assertEquals(
            "empty rows must render the honest Empty state",
            activity.getString(R.string.vvm_state_empty),
            emptyState.text.toString(),
        )
        activity.renderRows(listOf(row(1L), row(2L)))
        assertEquals("rows must be submitted to the adapter", 2, list.adapter!!.itemCount)
        assertEquals("the list must be visible with rows", View.VISIBLE, list.visibility)

        // --- Detail views present in the row layout. ---
        val row = LayoutInflater.from(activity).inflate(R.layout.item_voicemail_row, null)
        assertNotNull("the row must carry a play/pause control", row.findViewById(R.id.vvm_row_play))
        assertNotNull("the row must carry a speaker control", row.findViewById(R.id.vvm_row_speaker))
        assertNotNull("the row must carry a call-back control", row.findViewById(R.id.vvm_row_call_back))
        assertNotNull("the row must carry a delete control", row.findViewById(R.id.vvm_row_delete))

        // --- Detail seams route to the player under Robolectric. ---
        val uri = Uri.parse("content://com.piercingxx.xxdialer/voicemail/1")
        ShadowMediaPlayer.setCreateListener { _, _ -> }
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(activity, uri),
            ShadowMediaPlayer.MediaInfo(1, 1000),
        )
        assertTrue("play must route to the detail player", activity.toggleDetailPlay(uri))
        assertTrue("speaker on must route to the detail player", activity.setDetailSpeakerphone(true))
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertTrue("the AudioManager must report speakerphone on", audioManager.isSpeakerphoneOn)
        // Call back and delete delegate to the player's default seams (which run,
        // returning a Boolean, under Robolectric without a live provider).
        activity.callBackVoicemail("+15551234567")
        activity.deleteVoicemail(uri)
        ShadowMediaPlayer.setCreateListener(null)
    }

    private fun row(id: Long) = VvmListRow(
        id = id,
        number = "+15551234567",
        timestampMillis = 1_700_000_000_000L,
        durationSeconds = 12,
        isRead = false,
        transcription = null,
        callerName = "Ada",
    )
}