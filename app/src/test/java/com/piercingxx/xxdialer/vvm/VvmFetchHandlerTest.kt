package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmFetchHandlerTest {

    @Test
    fun handleDownloadsForFetchAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://com.android.voicemail/voicemail/1")
        var downloaded: Uri? = null
        val ok = VvmFetchHandler.handle(
            context,
            Intent(VoicemailContract.ACTION_FETCH_VOICEMAIL).setData(uri),
        ) { _, target ->
            downloaded = target
            true
        }
        assertTrue(ok)
        assertEquals(uri, downloaded)
    }

    @Test
    fun handleIgnoresOtherActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ok = VvmFetchHandler.handle(
            context,
            Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.android.voicemail/voicemail/1")),
        ) { _, _ -> error("must not download for a non-fetch action") }
        assertFalse(ok)
    }

    @Test
    fun manifestRegistersNonExportedFetchReceiver() {
        val xml = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()
        assertTrue(xml.contains(".vvm.VvmFetchHandler"))
        assertTrue(xml.contains("android.intent.action.FETCH_VOICEMAIL"))
        val block = xml.substringAfter(".vvm.VvmFetchHandler")
        assertTrue(
            block.contains("android:exported=\"false\""),
            "fetch handler must be non-exported — it writes provider rows",
        )
    }
}
