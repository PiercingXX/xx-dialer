package com.piercingxx.xxdialer.vvm

/**
 * Pure upload decision seam (todo.md VVM V8, T1). A user action on a voicemail
 * row — deleting it, or playing it (which marks it seen) — must be reflected
 * back to the carrier mailbox over IMAP so the server-side message state stays
 * in sync with what the user did locally. This object owns that single routing
 * rule and the row-to-carrier identity mapping, so the delete/play wiring in
 * the uploader (T2) stays a one-line decision and is trivially testable without
 * any Android framework.
 */
object VvmUploadPolicy {

    /** The carrier upload a user action demands. */
    enum class UploadAction { DELETE, MARK_SEEN, NONE }

    /**
     * Routes a user action to the carrier upload it demands. Deleting a
     * voicemail uploads a DELETE; playing one uploads a MARK_SEEN. A delete
     * subsumes a seen-mark — there is no point marking seen a message that is
     * being deleted — so when both are requested the delete wins.
     */
    fun route(delete: Boolean, markSeen: Boolean): UploadAction = when {
        delete -> UploadAction.DELETE
        markSeen -> UploadAction.MARK_SEEN
        else -> UploadAction.NONE
    }

    /**
     * The carrier message identity for a VoicemailContract row. The row's local
     * `_ID` is the stable handle the app uses to reference the message across the
     * delete/play paths, and it is the identity the uploader targets on the
     * carrier mailbox, so a delete or seen-mark addresses the same message the
     * user acted on.
     */
    fun carrierMessageId(rowId: Long): String = rowId.toString()
}