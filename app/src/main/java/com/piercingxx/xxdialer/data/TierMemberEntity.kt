package com.piercingxx.xxdialer.data

import androidx.room.Entity

/**
 * Family group membership (§9, §11): LOOKUP_KEY + tier. `biz` is Business;
 * any other non-reserved name is a user group. Composite PK so one contact
 * can be in Business and Family at once.
 */
@Entity(tableName = "tier_member", primaryKeys = ["lookupKey", "tier"])
data class TierMemberEntity(
    val lookupKey: String,
    val tier: String,
    val addedAt: Long,
)
