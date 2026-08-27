package com.piercingxx.xxdialer.vvm

import android.telecom.PhoneAccountHandle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.VisualVoicemailService
import android.telephony.VisualVoicemailService.VisualVoicemailTask
import android.telephony.VisualVoicemailSms
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCellServiceConnected(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        gate(task) {
            // T3: ACTIVATE only when the toggle is on AND the carrier config is
            // valid (D5: protocol from KEY_VVM_TYPE_STRING). The gate owns the
            // toggle clause; this reads the carrier half and feeds the decision.
            val carrierConfigValid = carrierConfigValid(phoneAccountHandle)
            if (VvmGate.shouldActivate(toggleOn = true, carrierConfigValid = carrierConfigValid)) {
                // TODO T4: TelephonyManager.sendVisualVoicemailSms(ACTIVATE).
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
                // TODO T4: TelephonyManager.sendVisualVoicemailSms(DEACTIVATE),
                // setVisualVoicemailSmsFilterSettings(null), drop provider rows.
            }
            task.finish()
        }
    }

    override fun onStopped(task: VisualVoicemailTask) {
        // Task-lifecycle callback, not VVM work: always finish, no gate.
        task.finish()
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
}