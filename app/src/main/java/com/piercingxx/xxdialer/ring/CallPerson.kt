package com.piercingxx.xxdialer.ring

import android.app.Person
import android.provider.ContactsContract

/**
 * Caller identity on the incoming CallStyle card. Nope-Mode's zen rule
 * (and any Priority DND) allows calls from starred contacts only when the
 * notification carries a resolvable person URI — a name alone is not enough.
 *
 * Prefer the contacts lookup URI so Android reads the same `STARRED` flag
 * the People tab writes. Fall back to `tel:` so PhoneLookup can still match
 * a starred row when the mirror has a number but no key.
 */
object CallPerson {

    fun incoming(displayName: String, e164: String?, lookupKey: String?): Person {
        val builder = Person.Builder()
            .setName(displayName)
            .setImportant(true)
        uri(e164, lookupKey)?.let { builder.setUri(it) }
        return builder.build()
    }

    /** Contact lookup URI when we have a key; otherwise `tel:<e164>`. */
    fun uri(e164: String?, lookupKey: String?): String? =
        contactUri(lookupKey) ?: telUri(e164)

    fun contactUri(lookupKey: String?): String? {
        val key = lookupKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon()
            .appendPath(key)
            .build()
            .toString()
    }

    fun telUri(e164: String?): String? {
        val number = e164?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Literal `tel:+…` — Uri.fromParts encodes `+` as `%2B`, and
        // PhoneLookup / DND people matching expect the unencoded form.
        return "tel:$number"
    }
}
