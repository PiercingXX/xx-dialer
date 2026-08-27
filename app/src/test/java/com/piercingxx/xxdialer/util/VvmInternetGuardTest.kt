package com.piercingxx.xxdialer.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dry run of the verifyVvmInternetOnly detection logic (todo.md D1) against
 * fixture `aapt2 dump permissions` strings — no manifest is touched, no APK
 * built. The Gradle task in app/build.gradle.kts / probe/build.gradle.kts
 * carries this exact algorithm verbatim; these tests pin its behavior.
 */
class VvmInternetGuardTest {

    // The real manifest's VVM permission set: INTERNET + ACCESS_NETWORK_STATE
    // are ALLOWED (opt-in VVM IMAP), the voicemail permissions are unrelated
    // to networking and must not trip the guard.
    private val vvmDump = """
        package: com.piercingxx.xxdialer
        uses-permission: name='android.permission.INTERNET'
        uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
        uses-permission: name='android.permission.ADD_VOICEMAIL'
        uses-permission: name='android.permission.READ_VOICEMAIL'
        uses-permission: name='android.permission.WRITE_VOICEMAIL'
        uses-permission: name='android.permission.SEND_SMS'
        uses-permission: name='android.permission.READ_CONTACTS'
        uses-permission: name='android.permission.CALL_PHONE'
    """.trimIndent()

    @Test
    fun `INTERNET and ACCESS_NETWORK_STATE are allowed for opt-in VVM`() {
        assertTrue(VvmInternetGuard.isClean(vvmDump))
        assertEquals(emptyList(), VvmInternetGuard.violations(vvmDump))
    }

    @Test
    fun `a forbidden network permission is flagged`() {
        val dirty = vvmDump + "\n" +
            "uses-permission: name='android.permission.CHANGE_NETWORK_STATE'"
        assertEquals(
            listOf("uses-permission: name='android.permission.CHANGE_NETWORK_STATE'"),
            VvmInternetGuard.violations(dirty),
        )
        assertFalse(VvmInternetGuard.isClean(dirty))
    }

    @Test
    fun `WIFI and BLUETOOTH and NFC permissions are flagged`() {
        assertFalse(VvmInternetGuard.isClean("uses-permission: name='android.permission.ACCESS_WIFI_STATE'"))
        assertFalse(VvmInternetGuard.isClean("uses-permission: name='android.permission.CHANGE_WIFI_STATE'"))
        assertFalse(VvmInternetGuard.isClean("uses-permission: name='android.permission.BLUETOOTH'"))
        assertFalse(VvmInternetGuard.isClean("uses-permission: name='android.permission.NFC'"))
    }

    @Test
    fun `detection is case insensitive`() {
        assertFalse(VvmInternetGuard.isClean("uses-permission: name='Android.Permission.Change_Network_State'"))
        assertFalse(VvmInternetGuard.isClean("uses-permission: name='android.permission.change_network_state'"))
        assertFalse(VvmInternetGuard.isClean("ANDROID.PERMISSION.CHANGE_NETWORK_STATE"))
    }

    @Test
    fun `bare forbidden permission line without aapt2 prefix is flagged`() {
        assertEquals(
            listOf("android.permission.CHANGE_NETWORK_STATE"),
            VvmInternetGuard.violations("  android.permission.CHANGE_NETWORK_STATE  "),
        )
    }

    @Test
    fun `non-network telephony permissions do not trip the guard`() {
        val telephony = """
            uses-permission: name='android.permission.READ_PHONE_STATE'
            uses-permission: name='android.permission.CALL_PHONE'
            uses-permission: name='android.permission.VIBRATE'
            uses-permission: name='android.permission.WAKE_LOCK'
        """.trimIndent()
        assertTrue(VvmInternetGuard.isClean(telephony))
    }
}