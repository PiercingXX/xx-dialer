package com.piercingxx.xxdialer.vvm

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure upload routing seam (todo.md VVM V8, T1). A user action on a voicemail
 * row — deleting it, or playing it (which marks it seen) — must be reflected
 * back to the carrier mailbox over IMAP. This pins that the routing decision
 * and the row-to-carrier identity mapping are correct.
 */
class VvmUploadPolicyTest {

    @Test
    fun routesDeleteAndSeenUploads() {
        // Deleting a voicemail uploads a DELETE.
        assertEquals(
            VvmUploadPolicy.UploadAction.DELETE,
            VvmUploadPolicy.route(delete = true, markSeen = false),
            "deleting a voicemail must upload a delete to the carrier",
        )
        // Playing a voicemail marks it seen, which uploads a MARK_SEEN.
        assertEquals(
            VvmUploadPolicy.UploadAction.MARK_SEEN,
            VvmUploadPolicy.route(delete = false, markSeen = true),
            "playing a voicemail must upload a seen-mark to the carrier",
        )
        // A delete subsumes a seen-mark: no point marking seen a message being deleted.
        assertEquals(
            VvmUploadPolicy.UploadAction.DELETE,
            VvmUploadPolicy.route(delete = true, markSeen = true),
            "a delete must win over a seen-mark when both are requested",
        )
        // No user action demands no upload.
        assertEquals(
            VvmUploadPolicy.UploadAction.NONE,
            VvmUploadPolicy.route(delete = false, markSeen = false),
            "no user action must demand no upload",
        )
    }

    @Test
    fun mapsRowToCarrierIdentity() {
        // The row's local _ID is the identity the uploader targets on the carrier,
        // so a delete or seen-mark addresses the same message the user acted on.
        assertEquals("42", VvmUploadPolicy.carrierMessageId(42L))
        assertEquals("1", VvmUploadPolicy.carrierMessageId(1L))
    }
}