package com.piercingxx.xxphone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Verdict counts grouped by outcome over a time span (§16 weekly tallies). */
data class TallyRow(
    val verdict: String,
    val n: Int,
)

/** The reason-string store (R7, §11). Capped at 1000 rows, oldest-first. */
@Dao
interface ScreenLogDao {

    @Insert
    suspend fun insert(l: ScreenLogEntity): Long

    /** Repeat-caller probe (D10): any non-Block row for this number since ts. */
    @Query(
        """
        SELECT COUNT(*) FROM screen_log
        WHERE e164 = :e164 AND at >= :sinceMillis AND verdict != 'Block'
        """,
    )
    suspend fun silenceCountSince(e164: String, sinceMillis: Long): Int

    @Query("SELECT * FROM screen_log ORDER BY at DESC LIMIT :limit")
    suspend fun lastN(limit: Int): List<ScreenLogEntity>

    @Query("SELECT * FROM screen_log WHERE e164 = :e164 ORDER BY at DESC LIMIT 1")
    suspend fun latestFor(e164: String): ScreenLogEntity?

    @Query("UPDATE screen_log SET answered = :answered WHERE id = :id")
    suspend fun updateAnswered(id: Long, answered: Boolean)

    @Query(
        """
        SELECT verdict, COUNT(*) AS n FROM screen_log
        WHERE at >= :sinceMillis
        GROUP BY verdict
        """,
    )
    suspend fun talliesSince(sinceMillis: Long): List<TallyRow>

    @Query(
        """
        DELETE FROM screen_log WHERE id NOT IN
            (SELECT id FROM screen_log ORDER BY at DESC LIMIT 1000)
        """,
    )
    suspend fun pruneTo1000()

    /** Privacy control (Rules screen): wipe the reason store entirely. */
    @Query("DELETE FROM screen_log")
    suspend fun deleteAll()
}
