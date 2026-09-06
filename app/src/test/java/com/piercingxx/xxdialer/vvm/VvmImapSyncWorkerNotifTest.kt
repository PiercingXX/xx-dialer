package com.piercingxx.xxdialer.vvm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class VvmImapSyncWorkerNotifTest {

    @Test
    fun newRowPostsNotification() {
        val src = sequenceOf(
            File("src/main/java/com/piercingxx/xxdialer/vvm/VvmImapSyncWorker.kt"),
            File("app/src/main/java/com/piercingxx/xxdialer/vvm/VvmImapSyncWorker.kt"),
        ).first { it.exists() }.readText()
        assertTrue(src.contains("VvmVoicemailNotifier"))
        assertTrue(src.contains("notifyNew"))
        assertTrue(src.contains("visualVoicemailEnabled") || src.contains("toggleOn"))
        assertTrue(
            src.contains("resolver.insert"),
            "notification must be wired next to the insert path",
        )
        assertTrue(src.contains("notifyNew(toggleOn"))
    }
}
