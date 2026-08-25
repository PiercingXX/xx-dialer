package com.piercingxx.xxphone.util

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.piercingxx.xxphone.ServiceLocator

/**
 * "Ring next time" star write (§12 recovery affordance): STARRED=1 on the
 * provider row behind a number, located through the mirror's lookup key.
 *
 * The mirror row is updated in the same stroke so RingPolicy sees the star
 * on the very next call, not after the next sweep — the whole point of the
 * affordance is that the NEXT call rings.
 */
object StarContact {

    /**
     * False when the number has no contact row (unknown callers cannot be
     * starred — the star IS the contact star, §12) or the provider refuses
     * the write (Contact Scopes, revoked WRITE_CONTACTS). Never throws (§15).
     */
    suspend fun ringNextTime(context: Context, number: String): Boolean {
        val e164 = E164.normalize(number) ?: number
        val db = ServiceLocator.db(context)
        val mirror = runCatching { db.contactMirrorDao().findByE164(e164) }.getOrNull()
            ?: return false
        val wrote = try {
            context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                ContentValues().apply { put(ContactsContract.Contacts.STARRED, 1) },
                "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
                arrayOf(mirror.lookupKey),
            ) > 0
        } catch (t: Throwable) {
            Log.w(TAG, "star write refused", t)
            false
        }
        if (wrote) {
            runCatching {
                db.contactMirrorDao().upsertAll(
                    listOf(mirror.copy(starred = true, refreshedAt = System.currentTimeMillis())),
                )
            }
        }
        return wrote
    }

    private const val TAG = "StarContact"
}
