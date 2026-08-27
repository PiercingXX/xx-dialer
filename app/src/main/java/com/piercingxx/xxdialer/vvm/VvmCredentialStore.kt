package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypted persistence for VVM mailbox credentials (todo.md D5, T2). The
 * carrier delivers the mailbox password inside the STATUS/SYNC SMS; it must be
 * stored at rest encrypted and must never ride in the backup JSON or a log
 * line. The password is the one field the dialer treats as a secret: [save]
 * writes it to [EncryptedSharedPreferences], while [backupJson] and [logLine]
 * deliberately omit it.
 */
class VvmCredentialStore(private val context: Context) {

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Persists [creds] (including its password) to encrypted prefs. */
    suspend fun save(creds: VvmSms) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_TYPE, creds.type)
            .putString(KEY_FIELDS, gson.toJson(creds.fields))
            .apply()
    }

    /**
     * Loads the persisted credential record, or null when none has been saved.
     * The password is restored from the encrypted store so the IMAP client
     * (T3/T5) can authenticate.
     */
    suspend fun load(): VvmSms? = withContext(Dispatchers.IO) {
        val type = prefs.getString(KEY_TYPE, null) ?: return@withContext null
        val fieldsJson = prefs.getString(KEY_FIELDS, null) ?: return@withContext null
        @Suppress("UNCHECKED_CAST")
        val fields = gson.fromJson(fieldsJson, Map::class.java) as? Map<String, String>
            ?: return@withContext null
        VvmSms(type = type, fields = fields)
    }

    /**
     * JSON of [creds]'s fields EXCEPT the password (`pw`). This is the only
     * serialization that may feed a backup (todo.md D12) — the password must
     * never leave the encrypted store. Pure: it does not touch the encrypted
     * prefs, so the redaction invariant is unit-testable without an
     * AndroidKeyStore (Robolectric does not shadow it).
     */
    fun backupJson(creds: VvmSms): String =
        gson.toJson(redact(creds))

    /**
     * A single log line of the credential record with the password (`pw`)
     * stripped. Never log [VvmCredentialStore] contents directly — [logLine] is
     * the only sanctioned rendering for logs. `internal` so the unit test can
     * drive it directly (test sources compile as a friend module); it stays out
     * of the public API.
     */
    internal fun logLine(creds: VvmSms): String =
        "VVM creds: type=${creds.type} " +
            redact(creds).entries.joinToString(" ") { (k, v) -> "$k=$v" }

    /** Returns [creds]'s fields with the password field removed. */
    private fun redact(creds: VvmSms): Map<String, String> =
        creds.fields.filterKeys { it != PASSWORD_FIELD }

    private companion object {
        const val PREFS_NAME = "vvm_creds"
        const val KEY_TYPE = "type"
        const val KEY_FIELDS = "fields"
        const val PASSWORD_FIELD = "pw"
    }
}