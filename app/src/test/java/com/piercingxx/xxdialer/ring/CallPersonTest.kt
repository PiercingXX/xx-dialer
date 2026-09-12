package com.piercingxx.xxdialer.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallPersonTest {

    @Test
    fun lookupKey_becomesContactsLookupUri() {
        val uri = CallPerson.uri(e164 = "+15551212", lookupKey = "0r1-ABCDEF")
        assertEquals("content://com.android.contacts/contacts/lookup/0r1-ABCDEF", uri)
    }

    @Test
    fun blankLookupKey_fallsBackToTel() {
        assertEquals("tel:+15551212", CallPerson.uri(e164 = "+15551212", lookupKey = "  "))
        assertEquals("tel:+15551212", CallPerson.uri(e164 = "+15551212", lookupKey = null))
    }

    @Test
    fun missingIdentity_isNull() {
        assertNull(CallPerson.uri(e164 = null, lookupKey = null))
        assertNull(CallPerson.uri(e164 = "  ", lookupKey = "  "))
    }

    @Test
    fun incoming_attachesUriAndName() {
        val person = CallPerson.incoming("Ada", "+15551212", "star-key")
        assertEquals("Ada", person.name)
        assertTrue(person.isImportant)
        assertEquals(CallPerson.contactUri("star-key"), person.uri)
    }
}
