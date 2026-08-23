package com.piercingxx.xxphone.data

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

    @Query("SELECT * FROM tier_member")
    suspend fun all(): List<TierMemberEntity>

    @Query("SELECT lookupKey FROM tier_member WHERE tier = 'biz'")
    suspend fun bizKeys(): List<String>

    /** Import-only (§11 backup): wholesale replace inside one transaction. */
    @Query("DELETE FROM tier_member")
    suspend fun deleteAll()
}
