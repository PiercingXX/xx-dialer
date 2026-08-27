package com.piercingxx.xxdialer.util

/**
 * Canonical detector for the brand rule "no network use outside opt-in VVM"
 * (todo.md D1 / "verifyVvmInternetOnly"). Consumes raw `aapt2 dump
 * permissions` output.
 *
 * The dialer is offline by default; INTERNET + ACCESS_NETWORK_STATE are
 * declared only so the opt-in Visual voicemail client can talk to the carrier
 * mailbox over IMAP after the user turns the toggle on. Any OTHER network
 * permission (CHANGE_NETWORK_STATE, any WIFI/BLUETOOTH/NFC permission, …)
 * would be a signal of network use outside VVM and fails the build.
 *
 * The build enforces the same rule via `verifyVvmInternetOnly` in
 * app/build.gradle.kts and probe/build.gradle.kts, which carry a verbatim
 * copy of [violations] — keep all three in sync.
 */
object VvmInternetGuard {

    /** The only network permissions the dialer may declare (todo.md D1). */
    val ALLOWED_NETWORK_PERMISSIONS = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    /**
     * Network permissions that must never appear. Anything outside
     * [ALLOWED_NETWORK_PERMISSIONS] that would put the dialer on the network
     * beyond the VVM IMAP client is forbidden.
     */
    val FORBIDDEN_NETWORK_PERMISSIONS = listOf(
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.ACCESS_WIFI_MULTICAST_STATE",
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.NFC",
        "android.permission.USE_WIFI_P2P",
    )

    /** Lines from the dump that declare a forbidden network permission. */
    fun violations(dump: String): List<String> =
        dump.lineSequence()
            .map { it.trim() }
            .filter { line ->
                FORBIDDEN_NETWORK_PERMISSIONS.any { line.contains(it, ignoreCase = true) }
            }
            .toList()

    /** True when the APK's permission dump has no forbidden network permission. */
    fun isClean(dump: String): Boolean = violations(dump).isEmpty()
}