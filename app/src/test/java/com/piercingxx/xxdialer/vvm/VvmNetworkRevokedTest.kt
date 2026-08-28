package com.piercingxx.xxdialer.vvm

import com.piercingxx.xxdialer.ui.VoicemailActivity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The GrapheneOS Network-revoke detector (todo.md "Tab states", scenario 5).
 * GrapheneOS lets the user revoke the INTERNET permission from an app at
 * runtime; while revoked the app cannot reach the carrier mailbox, so the
 * voicemail tab must detect it and show the honest "network revoked"
 * explanation instead of crashing. Pins the detector's pure rule AND that the
 * running activity reaches it — [VoicemailActivity.isNetworkRevoked] delegates
 * to [VvmNetworkRevoked], so this test fails if the detector stops being wired
 * into the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmNetworkRevokedTest {

    @Test
    fun detectsDeniedInternetPermission() {
        // The pure detector: a granted INTERNET permission means the network is
        // reachable; a revoked one means the mailbox cannot be reached.
        assertFalse(
            VvmNetworkRevoked.isRevoked(internetPermissionGranted = true),
            "a granted INTERNET permission must not be reported as network revoked",
        )
        assertTrue(
            VvmNetworkRevoked.isRevoked(internetPermissionGranted = false),
            "a revoked INTERNET permission must be reported as network revoked",
        )

        // The running activity must reach the detector through its real call
        // site — this fails if VoicemailActivity stops delegating to it.
        val activity = Robolectric.buildActivity(VoicemailActivity::class.java).setup().get()
        assertFalse(
            activity.isNetworkRevoked(internetPermissionGranted = true),
            "the voicemail activity must not report network revoked while INTERNET is granted",
        )
        assertTrue(
            activity.isNetworkRevoked(internetPermissionGranted = false),
            "the voicemail activity must surface the revoked-network state via VvmNetworkRevoked",
        )
    }
}