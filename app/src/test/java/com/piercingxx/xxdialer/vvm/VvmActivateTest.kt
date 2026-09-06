package com.piercingxx.xxdialer.vvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ACTIVATE/DEACTIVATE must be real telephony calls, not TODO comments (repair
 * P0-2 / T2). Toggle-off must share the DEACTIVATE path (todo.md V1) so
 * persisting `"0"` is not a silent no-op.
 */
class VvmActivateTest {

    private fun source(relative: String): String =
        sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/$relative"),
            File("app/src/main/java/com/piercingxx/xxdialer/$relative"),
        ).first { it.exists() }.readText()

    private fun controllerSource(): String = source("vvm/VvmActivationController.kt")

    private fun serviceSource(): String = source("vvm/XxVisualVoicemailService.kt")

    @Test
    fun activateSendsVisualVoicemailSmsAndRegistersFilter() {
        val src = controllerSource()
        assertFalse(
            serviceSource().contains("TODO T4"),
            "production must not keep a TODO T4 ACTIVATE/DEACTIVATE comment",
        )
        assertTrue(
            src.contains("sendSms"),
            "ACTIVATE must route through the sendSms seam",
        )
        assertTrue(
            src.contains("sendVisualVoicemailSms"),
            "ACTIVATE must call TelephonyManager.sendVisualVoicemailSms",
        )
        assertTrue(
            src.contains("setVisualVoicemailSmsFilterSettings"),
            "ACTIVATE must register the SMS filter settings",
        )
        assertTrue(
            src.contains("//VVM:ACTIVATE:"),
            "ACTIVATE must send the OMTP ACTIVATE body",
        )
    }

    @Test
    fun deactivateSendsDeactivateClearsFilterAndDropsRows() {
        val src = controllerSource()
        assertTrue(
            src.contains("//VVM:DEACTIVATE:"),
            "DEACTIVATE must send the OMTP DEACTIVATE body",
        )
        assertTrue(
            src.contains("contentResolver.delete") || src.contains("deleteRows"),
            "DEACTIVATE must drop VoicemailContract rows",
        )
        assertTrue(
            src.contains("setVisualVoicemailSmsFilterSettings") || src.contains("setFilterSettings"),
            "DEACTIVATE must clear the SMS filter settings",
        )
        assertTrue(
            src.contains("setActivated(false)"),
            "DEACTIVATE must reset visual_voicemail_activated",
        )
    }

    @Test
    fun serviceWiresControllerForActivateAndDeactivate() {
        val src = serviceSource()
        assertTrue(src.contains("VvmActivationController"))
        assertTrue(src.contains(".activate()"))
        assertTrue(src.contains(".deactivate()"))
        assertTrue(src.contains("VvmImapPolicy.canSync") || src.contains("VvmImapTransport"))
    }

    @Test
    fun rulesToggleOffTearsDown() {
        val src = source("ui/RulesActivity.kt")
        assertTrue(
            src.contains("VvmGate.shouldDeactivate"),
            "Rules toggle-off must consult shouldDeactivate, not just persist 0",
        )
        assertTrue(src.contains("VvmActivationController"))
        assertTrue(src.contains(".deactivate()"))
        assertTrue(
            src.contains("VvmRuntimePermissions"),
            "first toggle-on must request ADD_VOICEMAIL / SEND_SMS if missing",
        )
    }

    @Test
    fun longPressOneStillDialsVoicemailScheme() {
        val src = source("ui/KeypadActivity.kt")
        assertTrue(src.contains("placeVoicemail") || src.contains("voicemail:"))
        val callManager = source("telecom/CallManager.kt")
        assertTrue(callManager.contains("voicemail"))
        assertTrue(callManager.contains("placeCall"))
    }
}
