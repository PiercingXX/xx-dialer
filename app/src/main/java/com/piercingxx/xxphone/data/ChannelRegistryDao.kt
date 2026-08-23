package com.piercingxx.xxphone.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Purpose → channel mapping, append-only by design (§4.3, §10). */
@Dao
interface ChannelRegistryDao {

    @Query("SELECT * FROM channel_registry WHERE purpose = :purpose LIMIT 1")
    suspend fun current(purpose: String): ChannelRegistryEntity?

    @Upsert
    suspend fun upsert(e: ChannelRegistryEntity)

    @Query("DELETE FROM channel_registry WHERE purpose = :purpose")
    suspend fun delete(purpose: String)
}
