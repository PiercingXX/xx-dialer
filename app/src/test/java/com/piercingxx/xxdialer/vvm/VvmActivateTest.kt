package com.piercingxx.xxdialer.vvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ACTIVATE/DEACTIVATE must be real telephony calls, not TODO comments (repair
 * P0-2 / T2). The production service must send the OMTP ACTIVATE/DEACTIVATE SMS
 * via [android.telephony.TelephonyManager.sendVisualVoicemailSms], register and
 * clear the SMS filter, and tear down provider rows on DEACTIVATE. This test
 * fails the moment the production methods regress to `// TODO T4` comments or
 * drop the telephony call sites.
 */
class VvmActivateTest {

    private fun serviceSource(): String =
        sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/vvm/XxVisualVoicemailService.kt"),
        ).first { it.exists() }.readText()

    @Test
    fun activateSendsVisualVoicemailSmsAndRegistersFilter() {
        val src = serviceSource()
        assertFalse(
            src.contains("TODO T4"),
            "production must not keep a TODO T4 ACTIVATE/DEACTIVATE comment",
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
        val src = serviceSource()
        assertTrue(
            src.contains("//VVM:DEACTIVATE:"),
            "DEACTIVATE must send the OMTP DEACTIVATE body",
        )
        assertTrue(
            src.contains("contentResolver.delete"),
            "DEACTIVATE must drop VoicemailContract rows",
        )
        assertTrue(
            src.contains("setVisualVoicemailSmsFilterSettings"),
            "DEACTIVATE must clear the SMS filter settings",
        )
    }
}