package com.piercingxx.xxphone.telecom

import android.telecom.TelecomManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.4 STIR semantics + §4.2 presentation mapping, pinned against the API contract. */
class DetailsCodecTest {

    @Test
    fun stir_failedIsTheOnlySuspiciousStatus() {
        assertFalse(DetailsCodec.stirFailed(DetailsCodec.STIR_NOT_VERIFIED))
        assertFalse(DetailsCodec.stirFailed(DetailsCodec.STIR_PASSED))
        assertTrue(DetailsCodec.stirFailed(DetailsCodec.STIR_FAILED))
        assertFalse(DetailsCodec.stirFailed(99)) // unknown statuses are never suspicious
    }

    @Test
    fun stir_labels() {
        assertEquals("not_verified", DetailsCodec.stirLabel(DetailsCodec.STIR_NOT_VERIFIED))
        assertEquals("passed", DetailsCodec.stirLabel(DetailsCodec.STIR_PASSED))
        assertEquals("failed", DetailsCodec.stirLabel(DetailsCodec.STIR_FAILED))
    }

    @Test
    fun withheld_everythingNotAllowed() {
        assertFalse(DetailsCodec.isWithheld(TelecomManager.PRESENTATION_ALLOWED))
        assertTrue(DetailsCodec.isWithheld(TelecomManager.PRESENTATION_RESTRICTED))
        assertTrue(DetailsCodec.isWithheld(TelecomManager.PRESENTATION_UNKNOWN))
        assertTrue(DetailsCodec.isWithheld(TelecomManager.PRESENTATION_PAYPHONE))
    }

    @Test
    fun numberNormalization_garbageIsNull_neverLossy() {
        assertEquals("+14155550100", DetailsCodec.numberE164("+14155550100"))
        assertEquals("+14155550100", DetailsCodec.numberE164("415 555 0100"))
        assertNull(DetailsCodec.numberE164(null))
        assertNull(DetailsCodec.numberE164("not-a-number"))
    }

    @Test
    fun cnap_onlyWhenAllowedAndNotBlank() {
        assertEquals("EVERGREEN DENTAL", DetailsCodec.cnapName("EVERGREEN DENTAL", TelecomManager.PRESENTATION_ALLOWED))
        assertNull(DetailsCodec.cnapName("EVERGREEN DENTAL", TelecomManager.PRESENTATION_RESTRICTED))
        assertNull(DetailsCodec.cnapName("  ", TelecomManager.PRESENTATION_ALLOWED))
        assertNull(DetailsCodec.cnapName(null, TelecomManager.PRESENTATION_ALLOWED))
    }
}
