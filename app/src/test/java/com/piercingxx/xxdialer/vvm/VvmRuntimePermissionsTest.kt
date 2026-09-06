package com.piercingxx.xxdialer.vvm

import android.Manifest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class VvmRuntimePermissionsTest {

    @Test
    fun toggleOnRequestsAddVoicemailAndSendSms() {
        assertTrue(Manifest.permission.ADD_VOICEMAIL in VvmRuntimePermissions.TOGGLE_ON)
        assertTrue(Manifest.permission.SEND_SMS in VvmRuntimePermissions.TOGGLE_ON)
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/ui/RulesActivity.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/ui/RulesActivity.kt"),
        ).first { it.exists() }.readText()
        assertTrue(src.contains("VvmRuntimePermissions.missing"))
        assertTrue(src.contains("vvmPermissionLauncher") || src.contains("RequestMultiplePermissions"))
    }
}
