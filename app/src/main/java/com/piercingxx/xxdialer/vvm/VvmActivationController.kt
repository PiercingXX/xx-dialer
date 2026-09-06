package com.piercingxx.xxdialer.vvm

import android.content.Context
import android.provider.VoicemailContract
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailSmsFilterSettings
import android.util.Log
import com.piercingxx.xxdialer.ServiceLocator

/**
 * OMTP ACTIVATE / DEACTIVATE for the opt-in VVM client. Toggle-off and SIM
 * yank share this path so persisting `"0"` is not a silent no-op: we send
 * DEACTIVATE if we previously ACTIVATEd, clear the SMS filter, drop this
 * package's [VoicemailContract] rows, and reset the activated flag.
 *
 * Seams are named-with-default so [XxVisualVoicemailService] and the Rules
 * toggle can construct with `VvmActivationController(context)` while JVM
 * tests inject the four telephony/provider calls.
 */
class VvmActivationController(
    private val context: Context,
    private val sendSms: (dest: String?, port: Int, body: String) -> Unit = { dest, port, body ->
        context.getSystemService(TelephonyManager::class.java)
            ?.sendVisualVoicemailSms(dest, port, body, null)
    },
    private val setFilterSettings: (VisualVoicemailSmsFilterSettings?) -> Unit = { settings ->
        context.getSystemService(TelephonyManager::class.java)
            ?.setVisualVoicemailSmsFilterSettings(settings)
    },
    private val deleteRows: () -> Unit = {
        val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        context.contentResolver.delete(sourceUri, null, null)
    },
    private val setActivated: suspend (Boolean) -> Unit = { activated ->
        ServiceLocator.settings(context).setVisualVoicemailActivated(activated)
    },
    private val voiceMailNumber: () -> String? = {
        context.getSystemService(TelephonyManager::class.java)?.voiceMailNumber
    },
) {

    /** Send ACTIVATE, register the `//VVM:` SMS filter, persist the flag. */
    suspend fun activate() {
        val dest = voiceMailNumber()
        runCatching { sendSms(dest, VOICEMAIL_SMS_PORT, ACTIVATE_SMS_BODY) }
            .onFailure { Log.w(TAG, "vvm ACTIVATE SMS failed", it) }
        runCatching {
            setFilterSettings(
                VisualVoicemailSmsFilterSettings.Builder()
                    .setClientPrefix(CLIENT_PREFIX)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "vvm SMS filter register failed", it) }
        runCatching { setActivated(true) }
            .onFailure { Log.w(TAG, "vvm activated flag set failed", it) }
    }

    /**
     * Tear the mailbox down: DEACTIVATE SMS, clear filter, drop our provider
     * rows, forget the STATUS host / credentials, clear the activated flag.
     */
    suspend fun deactivate() {
        val dest = voiceMailNumber()
        runCatching { sendSms(dest, VOICEMAIL_SMS_PORT, DEACTIVATE_SMS_BODY) }
            .onFailure { Log.w(TAG, "vvm DEACTIVATE SMS failed", it) }
        runCatching { setFilterSettings(null) }
            .onFailure { Log.w(TAG, "vvm SMS filter clear failed", it) }
        runCatching { deleteRows() }
            .onFailure { Log.w(TAG, "vvm row drop failed", it) }
        VvmImapHostPolicy.clear()
        runCatching { VvmCredentialStore(context).clear() }
            .onFailure { Log.w(TAG, "vvm credential clear failed", it) }
        runCatching { setActivated(false) }
            .onFailure { Log.w(TAG, "vvm activated flag clear failed", it) }
    }

    companion object {
        const val TAG = "VvmActivation"
        const val VOICEMAIL_SMS_PORT = -1
        const val CLIENT_PREFIX = "//VVM:"
        const val ACTIVATE_SMS_BODY = "//VVM:ACTIVATE:"
        const val DEACTIVATE_SMS_BODY = "//VVM:DEACTIVATE:"
    }
}
