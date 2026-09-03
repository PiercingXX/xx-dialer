package com.piercingxx.xxdialer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A live call must stay reachable on a locked phone. The in-call surface
 * used to omit showWhenLocked / turnScreenOn, so proximity-off or a display
 * timeout dropped the user on the keyguard with no hang-up.
 */
class InCallLockManifestTest {

    private fun manifest(): String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun in_call_activity_shows_over_the_lock_and_wakes_the_display() {
        val xml = manifest()
        val block = xml.substring(xml.indexOf("android:name=\".ui.InCallActivity\""))
        val tag = block.substring(0, block.indexOf("/>") + 2)
        assertTrue(tag.contains("android:showWhenLocked=\"true\""), tag)
        assertTrue(tag.contains("android:turnScreenOn=\"true\""), tag)
    }
}
