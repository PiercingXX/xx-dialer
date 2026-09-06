package com.piercingxx.xxdialer.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.piercingxx.xxdialer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3 — VoicemailActivity computes and renders the honest state on load via
 * [VvmListState.decide], so the final copy is reachable from the running app.
 * Pins that [VoicemailActivity.onCreate] reaches the render path the moment
 * the tab opens: instead of a blank list or a lying Empty, the screen shows
 * Activating / NoCarrierConfig / NetworkRevoked until the mailbox is actually
 * healthy. This test fails if the on-load wiring is ever removed, because the
 * empty-state TextView would stay GONE and the honest copy would be unreachable.
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

        // The honest state must have been rendered on load via VvmListState.decide.
        // Without a SIM / ACTIVATEd mailbox the tab must not lie "Empty".
        val honest = setOf(
            activity.getString(R.string.vvm_state_no_config),
            activity.getString(R.string.vvm_state_activating),
            activity.getString(R.string.vvm_state_network_revoked),
        )
        assertTrue(
            "on-load copy must be Activating / NoCarrierConfig / NetworkRevoked, was: ${emptyState.text}",
            emptyState.text.toString() in honest,
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