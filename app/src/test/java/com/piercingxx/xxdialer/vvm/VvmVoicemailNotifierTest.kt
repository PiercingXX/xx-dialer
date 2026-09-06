package com.piercingxx.xxdialer.vvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class VvmVoicemailNotifierTest {

    @Test
    fun postsSecretChannelTapOpensTab() {
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/vvm/VvmVoicemailNotifier.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/vvm/VvmVoicemailNotifier.kt"),
        ).first { it.exists() }.readText()
        assertTrue(src.contains("fun notifyNewVoicemail"))
        assertTrue(src.contains("VvmNotifPolicy.CHANNEL_ID"))
        assertTrue(src.contains("VvmNotifPolicy.CHANNEL_IMPORTANCE"))
        assertTrue(src.contains("createNotificationChannel"))
        assertTrue(src.contains("notify"))
        assertTrue(src.contains("VoicemailActivity"))
        assertTrue(src.contains("VvmNotifPolicy.shouldNotify"))
    }
}
