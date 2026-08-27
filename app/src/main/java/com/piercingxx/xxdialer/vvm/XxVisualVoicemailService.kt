package com.piercingxx.xxdialer.vvm

import android.telecom.PhoneAccountHandle
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
        gate(task) { /* T3: ACTIVATE when the CarrierConfig is valid. */ }
    }

    override fun onSmsReceived(task: VisualVoicemailTask, message: VisualVoicemailSms) {
        gate(task) { /* T4: parse STATUS/SYNC; T5 IMAP sync. */ }
    }

    override fun onSimRemoved(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        gate(task) { /* T4: DEACTIVATE if previously activated, unregister, drop rows. */ }
    }

    override fun onStopped(task: VisualVoicemailTask) {
        // Task-lifecycle callback, not VVM work: always finish, no gate.
        task.finish()
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