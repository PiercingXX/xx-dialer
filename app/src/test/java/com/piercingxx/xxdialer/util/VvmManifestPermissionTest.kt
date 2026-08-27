package com.piercingxx.xxdialer.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manifest gate for the opt-in VVM (visual voicemail) permission set
 * (todo.md "Permissions" / D1). The dialer stays offline by default; when the
 * user turns the VVM toggle on the client talks to the carrier mailbox over
 * IMAP, so INTERNET + ACCESS_NETWORK_STATE are part of the declared set — the
 * R8 "no INTERNET, ever" rule is superseded for VVM by verifyVvmInternetOnly
 * (todo.md D1 / "verifyVvmInternetOnly"). This test pins that the manifest
 * actually declares the whole set, so a future edit that drops one of them
 * fails the build.
 *
 * BIND_VISUAL_VOICEMAIL_SERVICE is deliberately NOT in the list: it belongs on
 * the <service> tag (others bind to us), not in uses-permission (todo.md
 * "Permissions").
 */
class VvmManifestPermissionTest {

    private fun manifest(): String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    private val vvmPermissionSet = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ADD_VOICEMAIL",
        "android.permission.READ_VOICEMAIL",
        "android.permission.WRITE_VOICEMAIL",
        "android.permission.SEND_SMS",
    )

    @Test
    fun `manifest declares the full VVM permission set`() {
        val xml = manifest()
        vvmPermissionSet.forEach { permission ->
            assertTrue(
                xml.contains("uses-permission android:name=\"$permission\""),
                "manifest must declare $permission for opt-in VVM",
            )
        }
    }

    @Test
    fun `BIND_VISUAL_VOICEMAIL_SERVICE is not a uses-permission`() {
        // todo.md "Permissions": BIND_VISUAL_VOICEMAIL_SERVICE sits on the
        // <service> tag (others bind to us), never in uses-permission.
        val xml = manifest()
        assertTrue(
            !xml.contains("uses-permission android:name=\"android.permission.BIND_VISUAL_VOICEMAIL_SERVICE\""),
            "BIND_VISUAL_VOICEMAIL_SERVICE must live on the service tag, not uses-permission",
        )
    }
}