package com.piercingxx.xxdialer.data

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The warm mirror of ContactsContract (§9, §11): provider-owned facts,
 * refreshed by observer + foreground sweep, never authoritative.
 *
 * [bizTier] is deliberately NOT a column — tier membership lives in
 * `tier_member` (§11). It is resolved by a JOIN in
 * [ContactMirrorDao.findByE164] and filled into this field so consumers
 * reading a mirror row see the full caller fact set in one round trip.
 */
@Entity(tableName = "contact_mirror", indices = [Index("e164")])
data class ContactMirrorEntity(
    @PrimaryKey val lookupKey: String,
    val e164: String,
    val displayName: String,
    val starred: Boolean,
    val customRingtone: String? = null,
    val sendToVoicemail: Boolean,
    val refreshedAt: Long,
) {

    /** Presence in the mirror IS saved-ness; only saved callers get rows (§9). */
    @Ignore
    val saved: Boolean = true

    /** Joined from `tier_member` at query time — see class doc. */
    @Ignore
    var bizTier: Boolean = false
}
