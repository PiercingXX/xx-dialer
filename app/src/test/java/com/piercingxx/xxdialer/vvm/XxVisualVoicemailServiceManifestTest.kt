package com.piercingxx.xxdialer.vvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Manifest gate for the opt-in VVM service (todo.md "Permissions" / D1). The
 * carrier binds to XxVisualVoicemailService with BIND_VISUAL_VOICEMAIL_SERVICE
 * — the permission lives on the <service> tag, never in uses-permission (the
 * sibling VvmManifestPermissionTest pins that). This test locks the service
 * declaration: the exact permission and the VisualVoicemailService action, so
 * a future edit that drops either fails the build.
 */
class XxVisualVoicemailServiceManifestTest {

    private fun manifest(): String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun `manifest declares the vvm service with the exact permission`() {
        val xml = manifest()
        assertTrue(
            xml.contains(
                "<service\n" +
                    "            android:name=\".vvm.XxVisualVoicemailService\"\n" +
                    "            android:exported=\"true\"\n" +
                    "            android:permission=\"android.permission.BIND_VISUAL_VOICEMAIL_SERVICE\">",
            ),
            "manifest must declare the vvm service with BIND_VISUAL_VOICEMAIL_SERVICE",
        )
    }

    @Test
    fun `manifest registers the vvm service for the VisualVoicemailService action`() {
        val xml = manifest()
        assertTrue(
            xml.contains("android:name=\"android.telephony.VisualVoicemailService\""),
            "manifest must register the vvm service for the VisualVoicemailService action",
        )
    }

    @Test
    fun `declared vvm service name resolves to a class`() {
        // Throws ClassNotFoundException if the declared name is not real.
        Class.forName("com.piercingxx.xxdialer.vvm.XxVisualVoicemailService")
    }
}