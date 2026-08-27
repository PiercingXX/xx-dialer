package com.piercingxx.xxdialer.vvm

import android.content.ContentValues
import android.content.Context
import android.provider.VoicemailContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The voicemail list query (todo.md VVM list, T2): reads the mailbox rows from
 * VoicemailContract and resolves each caller's name. The rows are written by the
 * IMAP sync worker ([VvmImapSyncWorker]) exactly as they arrive from the carrier,
 * so this pins the read side end to end — insert rows, [VvmListQuery] reads them
 * and resolves names through the injectable resolver seam (defaulting to the
 * same PhoneLookup live path [ContactMirror] uses at ring time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VvmListQueryTest {

    @Before
    fun setUp() {
        // Robolectric has no working VoicemailContract provider (see
        // InMemoryVoicemailProvider), so register the in-memory one and start
        // every test from a clean mailbox.
        InMemoryVoicemailProvider.register()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.delete(
            VoicemailContract.Voicemails.buildSourceUri(context.packageName),
            null,
            null,
        )
    }

    /**
     * The task's verify method: insert two voicemail rows (one from a known
     * caller, one from an unknown number) and assert [VvmListQuery.readRows]
     * reads every row, newest first, and resolves each caller name through the
     * resolver seam — the known number gets its name, the unknown one stays null.
     */
    @Test
    fun readsRowsAndResolvesNames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        insertVoicemail(context, number = "+15551234567", date = 1_700_000_000_000L, duration = 42, isRead = 0)
        insertVoicemail(context, number = "+15559876543", date = 1_700_000_000_100L, duration = 17, isRead = 1)

        // Inject a resolver so the name-resolution seam is exercised directly
        // (the default PhoneLookup path is pinned separately below).
        val query = VvmListQuery(context) { _, number ->
            if (number == "+15551234567") "Ada Lovelace" else null
        }
        val rows = query.readRows()

        assertEquals("both inserted voicemails must be read", 2, rows.size)
        // Newest first (DATE desc): the 1_700_000_000_100L row leads.
        assertEquals(
            "the list must be newest first",
            1_700_000_000_100L,
            rows[0].timestampMillis,
        )
        assertEquals("+15559876543", rows[0].number)
        assertNull(
            "an unknown caller must resolve to no name, not a fabricated one",
            rows[0].callerName,
        )
        assertEquals("+15551234567", rows[1].number)
        assertEquals(
            "a known caller's number must resolve to its display name",
            "Ada Lovelace",
            rows[1].callerName,
        )
        assertEquals(42, rows[1].durationSeconds)
        assertTrue("the row with isRead=0 must come back unread", !rows[1].isRead)
        assertTrue("the row with isRead=1 must come back read", rows[0].isRead)
    }

    /**
     * The default resolver must use the live PhoneLookup path (the same query
     * ContactMirror uses at ring time): a number present in the contact store
     * resolves to its display name, and one absent from the store stays null.
     */
    @Test
    fun defaultResolverUsesPhoneLookup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        insertContact(context, number = "+15551234567", name = "Grace Hopper")
        insertVoicemail(context, number = "+15551234567", date = 1_700_000_000_000L, duration = 42, isRead = 0)
        insertVoicemail(context, number = "+15559876543", date = 1_700_000_000_100L, duration = 17, isRead = 1)

        val rows = VvmListQuery(context).readRows()

        val known = rows.single { it.number == "+15551234567" }
        assertEquals(
            "a number in the contact store must resolve via PhoneLookup",
            "Grace Hopper",
            known.callerName,
        )
        val unknown = rows.single { it.number == "+15559876543" }
        assertNull("a number absent from the contact store must stay nameless", unknown.callerName)
    }

    private fun insertVoicemail(context: Context, number: String, date: Long, duration: Int, isRead: Int) {
        val resolver = context.contentResolver
        val sourceUri = VoicemailContract.Voicemails.buildSourceUri(context.packageName)
        val values = ContentValues().apply {
            put(VoicemailContract.Voicemails.NUMBER, number)
            put(VoicemailContract.Voicemails.DATE, date)
            put(VoicemailContract.Voicemails.DURATION, duration)
            put(VoicemailContract.Voicemails.IS_READ, isRead)
        }
        resolver.insert(sourceUri, values)
    }

    private fun insertContact(context: Context, number: String, name: String) {
        val resolver = context.contentResolver
        val account = android.accounts.Account("test", "com.example")
        val rawContactUri = resolver.insert(
            android.provider.ContactsContract.RawContacts.CONTENT_URI,
            ContentValues().apply {
                put(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                put(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            },
        )!!
        resolver.insert(
            android.provider.ContactsContract.Data.CONTENT_URI,
            ContentValues().apply {
                put(android.provider.ContactsContract.RawContacts.Data.RAW_CONTACT_ID, rawContactUri.lastPathSegment!!.toLong())
                put(android.provider.ContactsContract.RawContacts.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            },
        )
        resolver.insert(
            android.provider.ContactsContract.Data.CONTENT_URI,
            ContentValues().apply {
                put(android.provider.ContactsContract.RawContacts.Data.RAW_CONTACT_ID, rawContactUri.lastPathSegment!!.toLong())
                put(android.provider.ContactsContract.RawContacts.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                put(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            },
        )
    }
}