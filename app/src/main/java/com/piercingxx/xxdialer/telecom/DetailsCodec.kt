package com.piercingxx.xxdialer.telecom

import android.telecom.TelecomManager
import com.piercingxx.xxdialer.util.E164

/**
 * Decoders for the primitives `Call.Details` hands us. Pure inputs → pure
 * outputs so the mappings stay JVM-testable; the only android references are
 * compile-time constants, inlined at the call site.
 */
object DetailsCodec {

    // android.jar-35 does not ship Call.Details' verification-status constants;
    // values pinned to the public API contract (§4.4): NOT_VERIFIED dominates
    // and is never treated as suspicious — only FAILED is a spoof signal.
    const val STIR_NOT_VERIFIED = 0
    const val STIR_PASSED = 1
    const val STIR_FAILED = 2

    fun stirFailed(status: Int): Boolean = status == STIR_FAILED

    /** Machine key stored in screen_log.stir (§11). */
    fun stirLabel(status: Int): String = when (status) {
        STIR_PASSED -> "passed"
        STIR_FAILED -> "failed"
        else -> "not_verified"
    }

    /** Withheld = anything not explicitly allowed (§4.2: RESTRICTED/UNKNOWN/PAYPHONE never reach the screener). */
    fun isWithheld(presentation: Int): Boolean = presentation != TelecomManager.PRESENTATION_ALLOWED

    /**
     * E.164 identity (§6). Garbage → null. Callers must keep presentation
     * withheld separate: a null here on an ALLOWED handle is unknown, not hidden.
     */
    fun numberE164(schemeSpecificPart: String?): String? = E164.normalize(schemeSpecificPart)

    /** CNAP name (§6) — context only, never a verdict input. */
    fun cnapName(displayName: String?, presentation: Int): String? =
        displayName?.takeIf { it.isNotBlank() && presentation == TelecomManager.PRESENTATION_ALLOWED }
}
