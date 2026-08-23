package com.piercingxx.xxphone.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * The warm mirror (§9). [findByE164] is the screener's hot path — its JOIN
 * resolves `bizTier` from `tier_member` so one query yields the full caller
 * fact set (see [ContactMirrorEntity]).
 */
@Dao
interface ContactMirrorDao {

    @Upsert
    suspend fun upsertAll(rows: List<ContactMirrorEntity>)

    @Query("SELECT * FROM contact_mirror")
    suspend fun all(): List<ContactMirrorEntity>

    @Query(
        """
        SELECT contact_mirror.*,
               EXISTS(
                   SELECT 1 FROM tier_member
                   WHERE tier_member.lookupKey = contact_mirror.lookupKey
                     AND tier_member.tier = 'biz'
               ) AS bizTier
        FROM contact_mirror
        WHERE e164 = :e164
        LIMIT 1
        """,
    )
    suspend fun findByE164(e164: String): ContactMirrorEntity?

    @Query("SELECT COUNT(*) FROM contact_mirror")
    suspend fun count(): Int

    /** Mirror rows older than ts are stale; refresh, don't delete (§9). */
    @Query("DELETE FROM contact_mirror WHERE refreshedAt < :ts")
    suspend fun deleteStale(ts: Long)

    /**
     * Empty-grant / first-run degradation (§15): a successful provider read
     * that finds nobody replaces the mirror wholesale — stale facts must not
     * outlive their source.
     */
    @Query("DELETE FROM contact_mirror")
    suspend fun deleteAll()
}
