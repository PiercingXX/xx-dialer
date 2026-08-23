package com.piercingxx.xxphone.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Single-row emergency marker (R10, §11): id is always 1. */
@Dao
interface EmergencyMarkerDao {

    @Query("SELECT * FROM emergency_marker WHERE id = 1")
    suspend fun get(): EmergencyMarkerEntity?

    @Upsert
    suspend fun put(e: EmergencyMarkerEntity)
}
