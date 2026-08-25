package com.piercingxx.xxdialer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row emergency marker (R10, §11): after an outgoing emergency call,
 * screening/silencing is bypassed for 24 h. Wall time AND elapsed-realtime
 * are both stored — the stricter reading wins on clock moves (§15).
 */
@Entity(tableName = "emergency_marker")
data class EmergencyMarkerEntity(
    @PrimaryKey val id: Long = 1,
    val lastEmergencyCallAt: Long,
    val lastEmergencyElapsed: Long,
)
