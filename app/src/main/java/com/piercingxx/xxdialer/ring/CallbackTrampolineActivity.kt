package com.piercingxx.xxdialer.ring

import android.app.Activity
import android.os.Bundle
import com.piercingxx.xxdialer.telecom.CallManager

/**
 * One-shot trampoline turning a notification "Call back" tap into
 * TelecomManager.placeCall() via telecom.CallManager.place — §4.1: outgoing
 * calls go through placeCall(), NEVER Intent.ACTION_CALL (emergency dialing
 * must reach the platform's flow). Non-exported: reachable only through this
 * app's own PendingIntents. Theme.NoDisplay keeps the hop invisible; the
 * placeCall fires synchronously before finish().
 */
class CallbackTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(Intents.EXTRA_CALLBACK_E164)
        if (!number.isNullOrBlank()) {
            runCatching { CallManager.place(this, number) } // never crash a notification tap
        }
        finish()
    }
}
