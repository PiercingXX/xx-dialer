package com.piercingxx.xxphone.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Business-tier membership (§9, §11): the LOOKUP_KEYs XX-Phone itself owns,
 * assigned in-app because GrapheneOS stock Contacts offers no groups.
 */
@Entity(tableName = "tier_member")
data class TierMemberEntity(
    @PrimaryKey val lookupKey: String,
    val tier: String,
    val addedAt: Long,
)
