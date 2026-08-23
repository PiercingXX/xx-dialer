package com.piercingxx.xxphone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Digit-mask pattern rules (§8, §11). */
@Dao
interface PatternRuleDao {

    /** Active query for the Rules screen (§12). */
    @Query("SELECT * FROM pattern_rule ORDER BY createdAt")
    fun all(): Flow<List<PatternRuleEntity>>

    @Query("SELECT * FROM pattern_rule ORDER BY createdAt")
    suspend fun allOnce(): List<PatternRuleEntity>

    @Insert
    suspend fun insert(r: PatternRuleEntity): Long

    @Query("DELETE FROM pattern_rule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM pattern_rule WHERE preset IS NOT NULL ORDER BY createdAt")
    suspend fun presets(): List<PatternRuleEntity>

    /** Import-only (§11 backup): wholesale replace inside one transaction. */
    @Query("DELETE FROM pattern_rule")
    suspend fun deleteAll()
}
