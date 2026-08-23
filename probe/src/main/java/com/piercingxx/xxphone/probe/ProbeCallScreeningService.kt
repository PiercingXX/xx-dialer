package com.piercingxx.xxphone.probe

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager

class ProbeCallScreeningService : CallScreeningService() {

    override fun onCreate() {
        super.onCreate()
        ProbeLog.init(this)
        ProbeLog.log("screening", "event" to "service_created")
    }

    override fun onScreenCall(details: Call.Details) {
        val extras = details.extras
        ProbeLog.log(
            "screening",
            "event" to "screened",
            "direction" to directionName(details.callDirection),
            "handle" to (details.handle?.schemeSpecificPart ?: "none"),
            "presentation" to presentationName(details.handlePresentation),
            "verification_status" to verificationName(details.callerNumberVerificationStatus),
            "silent_ring_requested" to extras?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED),
            "note" to "contacts calls reach the screener only when it is also the default dialer"
        )
        respondAllow(details)
    }

    private fun respondAllow(details: Call.Details) {
        try {
            respondToCall(details, CallResponse.Builder().build())
        } catch (t: Throwable) {
            ProbeLog.log("screening", "event" to "respond_failed", "error" to "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun directionName(direction: Int): String = when (direction) {
        Call.Details.DIRECTION_INCOMING -> "INCOMING"
        Call.Details.DIRECTION_OUTGOING -> "OUTGOING"
        else -> "VALUE($direction)"
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
