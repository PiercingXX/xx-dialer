package com.piercingxx.xxdialer.vvm

import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailService
import android.telephony.VisualVoicemailService.VisualVoicemailTask
import android.telephony.VisualVoicemailSms
import android.telecom.PhoneAccountHandle
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
 * [VvmGate] owns that decision). Toggle-off teardown is [VvmActivationController],
 * shared with the Rules switch so persisting `"0"` is not a silent no-op.
 */
class XxVisualVoicemailService : VisualVoicemailService() {

    /** Off-main gate reads: the toggle lives in the Room `setting` table. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val activation: VvmActivationController by lazy { VvmActivationController(this) }

    /**
     * T3: the mailbox sync worker. Runs the IMAP fetch off the main thread under
     * a wake lock and writes the fetched messages into VoicemailContract. The
     * fetch seam is the real IMAP client entry point (T5): it loads the encrypted
     * STATUS credentials and opens a socket to the carrier mailbox — a socket
     * that is only ever opened after VvmImapHostPolicy allows the STATUS host
     * and [VvmImapPolicy.canSync] allows the current transport.
     */
    private val syncWorker: VvmImapSyncWorker by lazy {
        VvmImapSyncWorker(this) { creds ->
            VvmImapClient(creds).fetch(
                cellularDataRequired = VvmImapTransport.cellularDataRequired(this),
                onCellularData = VvmImapTransport.onCellularData(this),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCellServiceConnected(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        gate(task) {
            val carrierConfigValid = carrierConfigValid(phoneAccountHandle)
            if (VvmGate.shouldActivate(toggleOn = true, carrierConfigValid = carrierConfigValid)) {
                activation.activate()
            }
        }
    }

    override fun onSmsReceived(task: VisualVoicemailTask, message: VisualVoicemailSms) {
        gate(task) {
            val sms = VvmSmsParser.parse(message.messageBody)
            if (sms != null) {
                if (sms.type == "STATUS") {
                    sms.fields["srv"]?.let { VvmImapHostPolicy.recordStatusHost(it) }
                }
                scope.launch {
                    VvmCredentialStore(this@XxVisualVoicemailService).save(sms)
                    val written = syncWorker.sync(sms)
                    val fetcher = VvmAudioFetcher(this@XxVisualVoicemailService)
                    written.forEach { fetcher.fetchIfMissingContent(it) }
                }
            }
        }
    }

    override fun onSimRemoved(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        // Teardown even when the toggle is now off — that is the point — so
        // this reads the persisted "previously activated" flag rather than
        // routing through [gate] (which requires the toggle to be on).
        scope.launch {
            val previouslyActivated = runCatching {
                ServiceLocator.settings(this@XxVisualVoicemailService).visualVoicemailWasActivated()
            }.getOrDefault(false)
            if (VvmGate.shouldDeactivate(toggleOn = false, previouslyActivated = previouslyActivated)) {
                activation.deactivate()
            }
            task.finish()
        }
    }

    override fun onStopped(task: VisualVoicemailTask) {
        task.finish()
    }

    /**
     * A carrier config is valid for VVM when it declares a protocol — a
     * non-empty `KEY_VVM_TYPE_STRING` (todo.md D5). Absent config, an unknown
     * subscription, or an empty type all mean we must NOT ACTIVATE.
     */
    private fun carrierConfigValid(phoneAccountHandle: PhoneAccountHandle): Boolean {
        val telephony = getSystemService(TelephonyManager::class.java) ?: return false
        val subscriptionId = telephony.getSubscriptionId(phoneAccountHandle)
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return false
        val manager = getSystemService(CarrierConfigManager::class.java) ?: return false
        val config = manager.getConfigForSubId(subscriptionId) ?: return false
        return VvmCarrierConfig.isValid(config.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING))
    }

    /**
     * The opt-in gate (D4): when the toggle is off every callback no-ops —
     * [task] finishes immediately and the [work] block never runs. Reading the
     * toggle is a suspend DAO call, so the decision runs off the calling
     * thread; the task is always finished exactly once, in every path.
     */
    private fun gate(task: VisualVoicemailTask, work: suspend () -> Unit) {
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
}
