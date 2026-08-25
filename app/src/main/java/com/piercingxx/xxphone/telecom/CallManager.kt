package com.piercingxx.xxphone.telecom

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Outgoing calls go through TelecomManager.placeCall() and NEVER
 * Intent.ACTION_CALL — emergency dialing must reach the platform's flow,
 * not bounce through ACTION_CALL's confirmation (§4.1).
 */
object CallManager {

    fun place(activity: Activity, e164: String): Boolean {
        val telecom = activity.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false // no telecom on this build: fail closed for placing, never crash
        return try {
            telecom.placeCall(Uri.fromParts("tel", e164, null), Bundle.EMPTY)
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "placeCall refused — CALL_PHONE not granted", se)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "placeCall failed", t)
            false
        }
    }

    private const val TAG = "CallManager"
}
