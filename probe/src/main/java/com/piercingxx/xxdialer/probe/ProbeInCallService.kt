package com.piercingxx.xxdialer.probe

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager

class ProbeInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        ProbeLog.init(this)
        ProbeLog.log("incall", "event" to "service_created", "ringing_owner" to true)
    }

    override fun onCallAdded(call: Call) {
        logCallDetails(call, event = "call_added")
        if (call.details?.state == Call.STATE_RINGING) {
            RingPoster.post(this, source = "incoming_call_ringing")
        }
    }

    override fun onCallRemoved(call: Call) {
        logCallDetails(call, event = "call_removed")
        RingPoster.cancel(this)
    }

    override fun onSilenceRinger() {
        ProbeLog.event("incall", "event=on_silence_ringer trigger=user_volume_press action=ringer_stopped")
        RingPoster.cancel(this)
    }

    private fun logCallDetails(call: Call, event: String) {
        val details = call.details
        if (details == null) {
            ProbeLog.log("incall", "event" to event, "details" to "null")
            return
        }
        val extras = details.extras
        ProbeLog.log(
            "incall",
            "event" to event,
            "state" to stateName(details.state),
            "handle" to (details.handle?.schemeSpecificPart ?: "none"),
            "presentation" to presentationName(details.handlePresentation),
            "caller_display_name" to (details.callerDisplayName ?: "none"),
            "verification_status" to verificationName(details.callerNumberVerificationStatus),
            "silent_ring_requested" to extras?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED),
            "emergency_callback_time_present" to extras?.containsKey(Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS)
        )
    }

    private fun stateName(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_PULLING_CALL -> "PULLING_CALL"
        else -> "VALUE($state)"
    }

    private fun presentationName(presentation: Int): String = when (presentation) {
        TelecomManager.PRESENTATION_ALLOWED -> "ALLOWED"
        TelecomManager.PRESENTATION_RESTRICTED -> "RESTRICTED"
        TelecomManager.PRESENTATION_UNKNOWN -> "UNKNOWN"
        TelecomManager.PRESENTATION_PAYPHONE -> "PAYPHONE"
        PRESENTATION_MISSING -> "MISSING"
        else -> "VALUE($presentation)"
    }

    private fun verificationName(status: Int): String = when (status) {
        VERIFICATION_STATUS_PASSED -> "PASSED($status)"
        VERIFICATION_STATUS_FAILED -> "FAILED($status)"
        else -> "NOT_VERIFIED($status)"
    }

    private companion object {
        const val PRESENTATION_MISSING = 5
        const val VERIFICATION_STATUS_PASSED = 1
        const val VERIFICATION_STATUS_FAILED = 2
    }
}
