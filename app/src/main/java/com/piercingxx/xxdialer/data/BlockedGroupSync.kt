package com.piercingxx.xxdialer.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import com.piercingxx.xxdialer.ServiceLocator
import com.piercingxx.xxdialer.util.E164
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors Blocked-group membership onto the system blocklist so the
 * platform rejects the call before it can ring. Best-effort under role loss.
 */
object BlockedGroupSync {

    suspend fun sync(context: Context, lookupKey: String, blocked: Boolean) {
        if (lookupKey.isEmpty()) return
        val numbers = numbersFor(context, lookupKey)
        withContext(Dispatchers.IO) {
            if (blocked) numbers.forEach { insert(context, it) }
            else numbers.forEach { remove(context, it) }
        }
    }

    private suspend fun numbersFor(context: Context, lookupKey: String): Set<String> {
        val mirrored = runCatching {
            ServiceLocator.db(context).contactMirrorDao().all()
                .filter { it.lookupKey == lookupKey }
                .map { it.e164 }
        }.getOrDefault(emptyList())
        val fromContacts = runCatching { phonesFromContacts(context, lookupKey) }
            .getOrDefault(emptyList())
        return (mirrored + fromContacts)
            .mapNotNull { E164.normalize(it) ?: it.trim().takeIf(String::isNotEmpty) }
            .toSet()
    }

    private fun phonesFromContacts(context: Context, lookupKey: String): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
            arrayOf(lookupKey),
            null,
        )?.use { cursor ->
            val col = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (col < 0) return@use
            while (cursor.moveToNext()) {
                cursor.getString(col)?.let { out += it }
            }
        }
        return out
    }

    private fun insert(context: Context, number: String) {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) return
        if (BlockedNumberContract.isBlocked(context, number)) return
        runCatching {
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
                },
            )
        }
    }

    private fun remove(context: Context, number: String) {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) return
        runCatching { BlockedNumberContract.unblock(context, number) }
    }
}
