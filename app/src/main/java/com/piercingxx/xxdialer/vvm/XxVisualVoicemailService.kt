package com.piercingxx.xxdialer.vvm

import android.provider.VoicemailContract
import android.telecom.PhoneAccountHandle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailService
import android.telephony.VisualVoicemailService.VisualVoicemailTask
import android.telephony.VisualVoicemailSms
import android.telephony.VisualVoicemailSmsFilterSettings
import com.piercingxx.xxdialer.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opt-in Visual Voicemail (VVM) service (todo.md "Permissions" / D1). The
 * dialer is offline by default; the carrier binds to this service so we can
 * ACTIVATE/DEACTIVATE the mailbox and receive its SMS notifications. Until the
 * user turns the VVM toggle ON the service must do nothing — every callback
 * finishes its task immediately and the source is never registered (the
 * [VvmGate] owns that decision).
 */
class XxVisualVoicemailService : VisualVoicemailService() {

    /** Off-main gate reads: the toggle lives in the Room `setting` table. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * T3: the mailbox sync worker. Runs the IMAP fetch off the main thread under
     * a wake lock and writes the fetched messages into VoicemailContract. The
     * fetch seam is the real IMAP client entry point (T5): it loads the encrypted
     * STATUS credentials and opens a socket to the carrier mailbox — a socket
     * that is only ever opened after VvmImapHostPolicy allows the STATUS host.
     * The worker's own invariants are pinned by VvmImapSyncWorkerTest.
     */
    private val syncWorker: VvmImapSyncWorker by lazy {
        VvmImapSyncWorker(this) { creds ->
            // T5: the real IMAP client. It consults VvmImapHostPolicy before any
            // socket opens and VvmImapPolicy for the TLS preference, then returns
            // the fetched messages for the worker to write into VoicemailContract.
            VvmImapClient(creds).fetch()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCellServiceConnected(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        gate(task) {
            // T4: ACTIVATE only when the toggle is on AND the carrier config is
            // valid (D5: protocol from KEY_VVM_TYPE_STRING). The gate owns the
            // toggle clause; this reads the carrier half and feeds the decision.
            val carrierConfigValid = carrierConfigValid(phoneAccountHandle)
            if (VvmGate.shouldActivate(toggleOn = true, carrierConfigValid = carrierConfigValid)) {
                activateMailbox(phoneAccountHandle)
            }
        }
    }

    override fun onSmsReceived(task: VisualVoicemailTask, message: VisualVoicemailSms) {
        gate(task) {
            // T4: parse the STATUS/SYNC notification into a credential record.
            // A non-VVM or malformed body yields null and is dropped here — the
            // parser is the single entry point for mailbox credentials.
            val sms = VvmSmsParser.parse(message.messageBody)
            if (sms != null) {
                // T3: a STATUS notification names the mailbox host (`srv`); feed
                // it into the host-constraint seam so the IMAP connect path (T5)
                // may reach only this last STATUS host. A SYNC message carries no
                // new host and must not widen the allowed set.
                if (sms.type == "STATUS") {
                    sms.fields["srv"]?.let { VvmImapHostPolicy.recordStatusHost(it) }
                }
                // T2: persist the credential to encrypted prefs so T3/T5 can
                // open the IMAP connection to sms.fields["srv"]. The store
                // encrypts at rest and strips the password from backup/log.
                scope.launch {
                    VvmCredentialStore(this@XxVisualVoicemailService).save(sms)
                    // T3: after persisting the credential, run the mailbox sync
                    // so the fetched messages land in VoicemailContract. The
                    // worker runs off the main thread under a wake lock.
                    val written = syncWorker.sync(sms)
                    // T2: IMAP delivers message metadata only — every written row
                    // carries HASCONTENT 0, so its audio must be fetched from the
                    // carrier by broadcasting ACTION_FETCH_VOICEMAIL. The fetcher
                    // owns that broadcast and is reached here for each new row.
                    val fetcher = VvmAudioFetcher(this@XxVisualVoicemailService)
                    written.forEach { fetcher.fetchIfMissingContent(it) }
                }
            }
        }
    }

    override fun onSimRemoved(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        // T4: toggle off after on — DEACTIVATE only if we previously ACTIVATEd,
        // then unregister the source and drop provider rows. This teardown runs
        // even when the toggle is now off (that is the point), so it reads the
        // persisted "previously activated" flag directly rather than routing
        // through [gate] (which requires the toggle to be on).
        scope.launch {
            val previouslyActivated = runCatching {
                ServiceLocator.settings(this@XxVisualVoicemailService).visualVoicemailWasActivated()
            }.getOrDefault(false)
            if (VvmGate.shouldDeactivate(toggleOn = false, previouslyActivated = previouslyActivated)) {
                deactivateMailbox(phoneAccountHandle)
            }
            task.finish()
        }
    }

    override fun onStopped(task: VisualVoicemailTask) {
        // Task-lifecycle callback, not VVM work: always finish, no gate.
        task.finish()
    }

    /**
     * T4: send the OMTP ACTIVATE SMS and register the SMS filter so the carrier's
     * STATUS/SYNC notifications reach [onSmsReceived]. Runs only when the gate
     * (toggle on + valid carrier config) has already passed — never when the
     * toggle is off. Persists the "previously activated" flag so a later
     * toggle-off can DEACTIVATE.
     */
    private fun activateMailbox(phoneAccountHandle: PhoneAccountHandle) {
        val telephony = getSystemService(TelephonyManager::class.java) ?: return
        val subscriptionId = telephony.getSubscriptionId(phoneAccountHandle)
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        // OMTP ACTIVATE: the carrier's VVM client asks the platform to send the
        // activation SMS to the voicemail number, then filter the reply.
        val voicemailNumber = telephony.voiceMailNumber
        runCatching {
            telephony.sendVisualVoicemailSms(
                voicemailNumber,
                VOICEMAIL_SMS_PORT,
                ACTIVATE_SMS_BODY,
                null,
            )
        }
        runCatching {
            telephony.setVisualVoicemailSmsFilterSettings(
                VisualVoicemailSmsFilterSettings.Builder()
                    .setClientPrefix("//VVM:")
                    .build(),
            )
        }
        scope.launch {
            runCatching {
                ServiceLocator.settings(this@XxVisualVoicemailService).setVisualVoicemailActivated(true)
            }
        }
    }

    /**
     * T4: tear the mailbox down after a toggle-off (or SIM removal): send the
     * OMTP DEACTIVATE SMS, clear the SMS filter so no further notifications are
     * routed here, drop every VoicemailContract row this package wrote, and clear
     * the "previously activated" flag. Runs even when the toggle is now off —
     * that is the point.
     */
    private fun deactivateMailbox(phoneAccountHandle: PhoneAccountHandle) {
        val telephony = getSystemService(TelephonyManager::class.java)
        val voicemailNumber = telephony?.voiceMailNumber
        runCatching {
            telephony?.sendVisualVoicemailSms(
                voicemailNumber,
                VOICEMAIL_SMS_PORT,
                DEACTIVATE_SMS_BODY,
                null,
            )
        }
        runCatching {
            telephony?.setVisualVoicemailSmsFilterSettings(null)
        }
        runCatching {
            val sourceUri = VoicemailContract.Voicemails.buildSourceUri(packageName)
            contentResolver.delete(sourceUri, null, null)
        }
        scope.launch {
            runCatching {
                ServiceLocator.settings(this@XxVisualVoicemailService).setVisualVoicemailActivated(false)
            }
        }
    }

    /**
     * T3: a carrier config is valid for VVM when it declares a protocol — a
     * non-empty `KEY_VVM_TYPE_STRING` (todo.md D5). Absent config, an unknown
     * subscription, or an empty type all mean we must NOT ACTIVATE.
     */
    private fun carrierConfigValid(phoneAccountHandle: PhoneAccountHandle): Boolean {
        val telephony = getSystemService(TelephonyManager::class.java) ?: return false
        val subscriptionId = telephony.getSubscriptionId(phoneAccountHandle)
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return false
        val manager = getSystemService(CarrierConfigManager::class.java) ?: return false
        val config = manager.getConfigForSubId(subscriptionId) ?: return false
        return !config.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING).isNullOrEmpty()
    }

    /**
     * The opt-in gate (D4): when the toggle is off every callback no-ops —
     * [task] finishes immediately and the [work] block never runs. Reading the
     * toggle is a suspend DAO call, so the decision runs off the calling
     * thread; the task is always finished exactly once, in every path.
     */
    private fun gate(task: VisualVoicemailTask, work: () -> Unit) {
        scope.launch {
            val enabled = runCatching {
                ServiceLocator.settings(this@XxVisualVoicemailService).visualVoicemailEnabled()
            }.getOrDefault(false)
            if (VvmGate.shouldRunVvm(enabled)) {
                runCatching { work() }
            }
            task.finish()
        }
    }

    private companion object {
        /** OMTP VVM SMS port; the platform routes the activation reply. */
        const val VOICEMAIL_SMS_PORT = -1
        /** OMTP ACTIVATE body (todo.md D5). */
        const val ACTIVATE_SMS_BODY = "//VVM:ACTIVATE:"
        /** OMTP DEACTIVATE body (todo.md D5). */
        const val DEACTIVATE_SMS_BODY = "//VVM:DEACTIVATE:"
    }
}