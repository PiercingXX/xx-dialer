package com.piercingxx.xxdialer.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.piercingxx.xxdialer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3 — VoicemailActivity computes and renders the honest state on load, so the
 * final copy is reachable from the running app. Pins that [VoicemailActivity.onCreate]
 * reaches the render path the moment the tab opens: instead of a blank list, the
 * screen shows one honest [VvmListState]. Under Robolectric the INTERNET
 * permission is denied by default, so the honest state is [VvmListState.NetworkRevoked]
 * and its copy — exactly the GrapheneOS revoke scenario the feature exists for.
 * This test fails if the on-load wiring is ever removed, because the empty-state
 * TextView would stay GONE and the honest copy would be unreachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicemailActivityStateWiringTest {

    @Test
    fun computesAndRendersStateOnLoad() {
        // Building and setting up the activity drives the real call site: onCreate
        // must compute and render the honest state on load. Robolectric denies the
        // INTERNET permission by default, so the honest state is NetworkRevoked.
        val activity = Robolectric.buildActivity(VoicemailActivity::class.java).setup().get()

        val emptyState = activity.findViewById<TextView>(R.id.vvm_empty_state)
        val list = activity.findViewById<RecyclerView>(R.id.vvm_list)
        assertNotNull("the empty-state TextView must be present", emptyState)
        assertNotNull("the voicemail list must be present", list)

        // The honest state must have been rendered on load: with the network
        // revoked the NetworkRevoked state shows its copy and hides the list. If
        // onCreate never reached the render path, emptyState stays GONE and fails.
        assertEquals(
            "the honest NetworkRevoked copy must be rendered on load",
            activity.getString(R.string.vvm_state_network_revoked),
            emptyState.text.toString(),
        )
        assertEquals(
            "the empty-state message must be visible on load",
            View.VISIBLE,
            emptyState.visibility,
        )
        assertEquals(
            "the list must be hidden while the honest state is shown",
            View.GONE,
            list.visibility,
        )
    }
}