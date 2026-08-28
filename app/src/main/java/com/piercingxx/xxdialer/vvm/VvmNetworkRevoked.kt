package com.piercingxx.xxdialer.vvm

/**
 * Detector for GrapheneOS's Network revoke (todo.md "Tab states", scenario 5).
 * GrapheneOS lets the user revoke the INTERNET permission from an app at
 * runtime; while revoked the app cannot open any network socket and so cannot
 * reach the carrier mailbox. The voicemail tab must detect this and show the
 * honest "network revoked" state instead of crashing or lying with a fake-empty
 * list. Pure decision — no Android types, no I/O — so it is trivially
 * unit-testable and safe to call from any thread.
 */
object VvmNetworkRevoked {

    /**
     * True only when the app's INTERNET permission has been revoked, i.e. when
     * [internetPermissionGranted] is false. The mailbox is unreachable without
     * the network, so the tab must explain the failure rather than attempt a
     * fetch that is guaranteed to fail.
     */
    fun isRevoked(internetPermissionGranted: Boolean): Boolean = !internetPermissionGranted
}