package com.piercingxx.xxdialer.telecom

import android.telephony.PhoneNumberUtils
import android.util.Log
import com.piercingxx.xxdialer.data.EmergencyMarkerEntity
import com.piercingxx.xxdialer.data.XxDatabase

/**
 * The single-row `emergency_marker` table (R10, §11): after an outgoing
 * emergency call, all screening and silencing is bypassed for 24 h. Both
 * clock readings are stored; [EmergencyWindow] applies the stricter-of-two
 * rule (§15 clock row).
 */
class EmergencyMarker(db: XxDatabase) {

    private val dao = db.emergencyMarkerDao()

    /** Called when an outgoing number is an emergency number (see [isEmergency]). */
    suspend fun record(nowEpochMillis: Long, elapsedNowMillis: Long) {
        runCatching {
            dao.put(EmergencyMarkerEntity(id = 1, lastEmergencyCallAt = nowEpochMillis, lastEmergencyElapsed = elapsedNowMillis))
        }
            .onFailure { Log.w(TAG, "emergency marker write failed", it) }
        // On failure the platform's own 2 h blocking suppression still holds (§4.4);
        // our longer belt just misses this one emergency.
    }

    suspend fun activeWindow(nowEpochMillis: Long, elapsedNowMillis: Long): Boolean =
        runCatching { dao.get() }
            .onFailure { Log.w(TAG, "emergency marker read failed", it) }
            .getOrNull()
            ?.let {
                EmergencyWindow.active(nowEpochMillis, it.lastEmergencyCallAt, elapsedNowMillis, it.lastEmergencyElapsed)
            }
            ?: false

    companion object {
        private const val TAG = "EmergencyMarker"

        fun isEmergency(number: String?): Boolean =
            !number.isNullOrBlank() &&
                runCatching { PhoneNumberUtils.isEmergencyNumber(number) }
                    // R10 is a law: a throwing telephony stack silently
                    // disabling the 24 h window must at least say so.
                    .onFailure { Log.w(TAG, "isEmergencyNumber threw — R10 window cannot arm", it) }
                    .getOrDefault(false)
    }
}
