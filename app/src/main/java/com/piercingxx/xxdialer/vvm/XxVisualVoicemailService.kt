package com.piercingxx.xxdialer.vvm

import android.telecom.PhoneAccountHandle
import android.telephony.VisualVoicemailService
import android.telephony.VisualVoicemailService.VisualVoicemailTask
import android.telephony.VisualVoicemailSms

/**
 * Opt-in Visual Voicemail (VVM) service (todo.md "Permissions" / D1). The
 * dialer is offline by default; the carrier binds to this service so we can
 * ACTIVATE/DEACTIVATE the mailbox and receive its SMS notifications. Until the
 * user turns the VVM toggle ON the service must do nothing — every callback
 * finishes its task immediately and the source is never registered (the
 * [VvmGate] in later tasks owns that decision).
 */
class XxVisualVoicemailService : VisualVoicemailService() {

    override fun onCellServiceConnected(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        task.finish()
    }

    override fun onSmsReceived(task: VisualVoicemailTask, message: VisualVoicemailSms) {
        task.finish()
    }

    override fun onSimRemoved(task: VisualVoicemailTask, phoneAccountHandle: PhoneAccountHandle) {
        task.finish()
    }

    override fun onStopped(task: VisualVoicemailTask) {
        task.finish()
    }
}