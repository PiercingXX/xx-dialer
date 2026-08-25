package com.piercingxx.xxdialer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Purpose → notification-channel mapping (§10, §11): channels are
 * append-only, so a tone change mints `ring_unknown_v2` and this registry
 * keeps the mapping (§4.3).
 */
@Entity(tableName = "channel_registry")
data class ChannelRegistryEntity(
    @PrimaryKey val purpose: String,
    val channelId: String,
    val toneUri: String? = null,
    val version: Int,
)
