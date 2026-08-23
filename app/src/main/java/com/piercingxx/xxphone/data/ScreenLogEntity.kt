package com.piercingxx.xxphone.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One screened or ringing call — the reason-string store behind R7 and the
 * Recents annotations (§11). Capped at 1000 rows, pruned oldest-first
 * ([ScreenLogDao.pruneTo1000]).
 */
@Entity(tableName = "screen_log")
data class ScreenLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val e164: String? = null,
    val presentation: Int = 0,
    val verdict: String,
    val reason: String,
    val tier: String? = null,
    val stir: String,
    val cnapName: String? = null,
    val mode: String, // 'enforced' | 'observed' (§11)
    val answered: Boolean = false,
)
