package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.telephony.VisualVoicemailSmsFilterSettings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmActivationControllerTest {

    @Test
    fun activateSendsSmsRegistersFilterAndSetsFlag() = runBlocking {
        var smsBody: String? = null
        var filter: VisualVoicemailSmsFilterSettings? = VisualVoicemailSmsFilterSettings.Builder().build()
        var activated: Boolean? = null
        val controller = VvmActivationController(
            context = ApplicationProvider.getApplicationContext(),
            sendSms = { _, _, body -> smsBody = body },
            setFilterSettings = { filter = it },
            deleteRows = { error("activate must not drop rows") },
            setActivated = { activated = it },
            voiceMailNumber = { "+15551234567" },
        )

        controller.activate()

        assertEquals("//VVM:ACTIVATE:", smsBody)
        assertTrue("ACTIVATE must register a non-null SMS filter", filter != null)
        assertEquals(true, activated)
    }

    @Test
    fun deactivateSendsSmsClearsFilterDropsRowsAndFlag() = runBlocking {
        var smsBody: String? = null
        var filter: VisualVoicemailSmsFilterSettings? = VisualVoicemailSmsFilterSettings.Builder().build()
        var deleted = false
        var activated: Boolean? = true
        VvmImapHostPolicy.recordStatusHost("mail.example.com")
        val controller = VvmActivationController(
            context = ApplicationProvider.getApplicationContext(),
            sendSms = { _, _, body -> smsBody = body },
            setFilterSettings = { filter = it },
            deleteRows = { deleted = true },
            setActivated = { activated = it },
            voiceMailNumber = { "+15551234567" },
        )

        controller.deactivate()

        assertEquals("//VVM:DEACTIVATE:", smsBody)
        assertNull("DEACTIVATE must clear the SMS filter", filter)
        assertTrue("DEACTIVATE must drop VoicemailContract rows", deleted)
        assertEquals(false, activated)
        assertFalse(
            "DEACTIVATE must forget the STATUS host so no socket target remains",
            VvmImapHostPolicy.canConnectTo("mail.example.com"),
        )
    }
}
