package com.piercingxx.xxdialer.telecom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import com.piercingxx.xxdialer.ui.InCallActivity

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
            openInCallScreen(activity)
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "placeCall refused — CALL_PHONE not granted", se)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "placeCall failed", t)
            false
        }
    }

    /**
     * §12 in-call: the surface follows the call immediately — without this
     * an outgoing call leaves the user staring at the keypad with only the
     * ongoing notification as a way in.
     */
    private fun openInCallScreen(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(activity, InCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /**
     * Voicemail dial (§12 long-press 1): the voicemail: scheme lets Telecom
     * resolve the carrier's number from the PhoneAccount — the number itself
     * never needs to be known to this app.
     */
    fun placeVoicemail(activity: Activity): Boolean {
        val telecom = activity.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false
        return try {
            telecom.placeCall(Uri.fromParts("voicemail", "", null), Bundle.EMPTY)
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "voicemail call refused — CALL_PHONE not granted", se)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "voicemail call failed", t)
            false
        }
    }

    private const val TAG = "CallManager"
}
