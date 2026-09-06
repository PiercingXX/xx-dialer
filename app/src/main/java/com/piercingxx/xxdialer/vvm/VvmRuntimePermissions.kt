package com.piercingxx.xxdialer.vvm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Runtime permissions the first VVM toggle-on must request if ROLE_DIALER
 * did not auto-grant them. ADD_VOICEMAIL backs VoicemailContract writes;
 * SEND_SMS is the ACTIVATE/DEACTIVATE channel.
 */
object VvmRuntimePermissions {

    val TOGGLE_ON = arrayOf(
        Manifest.permission.ADD_VOICEMAIL,
        Manifest.permission.SEND_SMS,
    )

    fun missing(context: Context): Array<String> =
        TOGGLE_ON.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
