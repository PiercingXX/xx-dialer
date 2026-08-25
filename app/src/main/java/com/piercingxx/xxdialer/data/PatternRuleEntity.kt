package com.piercingxx.xxdialer.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A digit-mask rule (§8, §11): E.164 prefix + masked trailing digits —
 * `425-555-XXXX` is prefix `1425555`, wildcards 4.
 */
@Entity(tableName = "pattern_rule")
data class PatternRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val e164Prefix: String,
    val wildcards: Int,
    val action: String, // 'block' | 'silence' (§11)
    val preset: String? = null,
    val createdAt: Long,
)
