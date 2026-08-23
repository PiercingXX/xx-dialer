package com.piercingxx.xxphone.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dry run of the verifyNoInternet detection logic (design.md R8/D8) against
 * fixture `aapt2 dump permissions` strings — no manifest is touched, no APK
 * built. The Gradle task in app/build.gradle.kts / probe/build.gradle.kts
 * carries this exact algorithm verbatim; these tests pin its behavior.
 */
class NoInternetGuardTest {

    private val cleanDump = """
        package: com.piercingxx.xxphone
        uses-permission: name='android.permission.READ_CONTACTS'
        uses-permission: name='android.permission.READ_CALL_LOG'
        uses-permission: name='android.permission.READ_PHONE_STATE'
        uses-permission: name='android.permission.CALL_PHONE'
        uses-permission: name='android.permission.POST_NOTIFICATIONS'
        uses-permission: name='android.permission.USE_FULL_SCREEN_INTENT'
        uses-permission: name='android.permission.FOREGROUND_SERVICE'
        uses-permission: name='android.permission.FOREGROUND_SERVICE_PHONE_CALL'
        uses-permission: name='android.permission.VIBRATE'
        permission: com.piercingxx.xxphone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
    """.trimIndent()

    private val dirtyDump = """
        package: com.piercingxx.xxphone
        uses-permission: name='android.permission.READ_CONTACTS'
        uses-permission: name='android.permission.INTERNET'
        uses-permission: name='android.permission.VIBRATE'
    """.trimIndent()

    @Test
    fun `clean aapt2 dump passes`() {
        assertTrue(NoInternetGuard.isClean(cleanDump))
        assertEquals(emptyList(), NoInternetGuard.violations(cleanDump))
    }

    @Test
    fun `fixture containing INTERNET is flagged`() {
        assertEquals(
            listOf("uses-permission: name='android.permission.INTERNET'"),
            NoInternetGuard.violations(dirtyDump),
        )
        assertFalse(NoInternetGuard.isClean(dirtyDump))
    }

    @Test
    fun `detection is case insensitive`() {
        assertFalse(NoInternetGuard.isClean("uses-permission: name='Android.Permission.Internet'"))
        assertFalse(NoInternetGuard.isClean("ANDROID.PERMISSION.INTERNET"))
        assertFalse(NoInternetGuard.isClean("android.permission.internet"))
    }

    @Test
    fun `bare permission line without aapt2 prefix is flagged`() {
        assertFalse(NoInternetGuard.isClean("android.permission.INTERNET"))
        assertEquals(
            listOf("android.permission.INTERNET"),
            NoInternetGuard.violations("  android.permission.INTERNET  "),
        )
    }

    @Test
    fun `similar permissions do not trip the guard`() {
        // e.g. ACCESS_NETWORK_STATE or CHANGE_NETWORK_STATE must stay allowed.
        val nearMiss = """
            uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
            uses-permission: name='android.permission.CHANGE_WIFI_MULTICAST_STATE'
        """.trimIndent()
        assertTrue(NoInternetGuard.isClean(nearMiss))
    }
}
