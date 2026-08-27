package com.piercingxx.xxdialer.vvm

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The VVM credential store must keep the mailbox password out of every
 * non-encrypted surface (todo.md D5, T2): the backup JSON and any log line.
 * This test pins that invariant — a saved password 'secret123' must not appear
 * in either [VvmCredentialStore.backupJson] or [VvmCredentialStore.logLine].
 *
 * The redaction surfaces are pure (they take the [VvmSms] directly) so they are
 * testable without the AndroidKeyStore, which Robolectric does not shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmCredentialStoreTest {

    @Test
    fun passwordNeverInBackupOrLog() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = VvmCredentialStore(context)

        val creds = VvmSms(
            type = "STATUS",
            fields = mapOf(
                "srv" to "mail.example.com",
                "u" to "alice",
                "pw" to "secret123",
                "st" to "2",
                "rc" to "1",
            ),
        )

        // The password must never appear in the backup JSON or the log line.
        assertFalse(
            "backupJson() must not contain the password",
            store.backupJson(creds).contains("secret123"),
        )
        assertFalse(
            "logLine() must not contain the password",
            store.logLine(creds).contains("secret123"),
        )
    }
}