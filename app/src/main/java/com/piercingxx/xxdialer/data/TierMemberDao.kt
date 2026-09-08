package com.piercingxx.xxdialer.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Business-tier storage (§9). */
@Dao
interface TierMemberDao {

    @Upsert
    suspend fun upsert(e: TierMemberEntity)

    @Query("DELETE FROM tier_member WHERE lookupKey = :lookupKey")
    suspend fun delete(lookupKey: String)

    @Query("DELETE FROM tier_member WHERE lookupKey = :lookupKey AND tier = :tier")
    suspend fun delete(lookupKey: String, tier: String)

    @Query("SELECT DISTINCT tier FROM tier_member WHERE tier != 'biz' ORDER BY tier COLLATE NOCASE")
    suspend fun customGroupNames(): List<String>

    @Query("SELECT lookupKey FROM tier_member WHERE tier = :tier")
    suspend fun keysFor(tier: String): List<String>

    @Query("SELECT * FROM tier_member WHERE tier != 'biz'")
    suspend fun customGroups(): List<TierMemberEntity>

    @Query("SELECT * FROM tier_member")
    suspend fun all(): List<TierMemberEntity>

    @Query("SELECT lookupKey FROM tier_member WHERE tier = 'biz'")
    suspend fun bizKeys(): List<String>

    /** Import-only (§11 backup): wholesale replace inside one transaction. */
    @Query("DELETE FROM tier_member")
    suspend fun deleteAll()
}
