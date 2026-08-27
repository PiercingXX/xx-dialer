package com.piercingxx.xxdialer.vvm

/**
 * The honest-state decision for the voicemail list screen (todo.md VVM list, T1).
 * The list must never lie to the user: instead of showing an empty list while the
 * mailbox is actually unavailable or not yet ready, it renders one of the honest
 * states below. This object owns that single mapping so [VoicemailActivity]'s
 * rendering stays a one-line `when` and the decision is trivially testable without
 * any Android framework.
 */
sealed class VvmListState {

    /** No carrier VVM config — the mailbox cannot be reached at all. */
    object NoCarrierConfig : VvmListState()

    /** The mailbox has not finished activating yet. */
    object Activating : VvmListState()

    /** The carrier has revoked network access to the mailbox. */
    object NetworkRevoked : VvmListState()

    /** An IMAP fetch failed — the mailbox could not be read. */
    object ImapError : VvmListState()

    /** The mailbox is reachable and healthy but holds no voicemails. */
    object Empty : VvmListState()

    /** The mailbox holds [voicemailCount] voicemails to render. */
    data class List(val voicemailCount: Int) : VvmListState()

    companion object {

        /**
         * Decides the honest state to show from the mailbox's raw inputs, in
         * precedence order:
         *
         * 1. No valid carrier config ⇒ [NoCarrierConfig] — nothing else matters.
         * 2. Not yet activated ⇒ [Activating].
         * 3. Network revoked ⇒ [NetworkRevoked].
         * 4. IMAP error ⇒ [ImapError].
         * 5. Zero voicemails ⇒ [Empty].
         * 6. Otherwise ⇒ [List] with the voicemail count.
         */
        fun decide(
            carrierConfigValid: Boolean,
            activated: Boolean,
            networkRevoked: Boolean,
            imapError: Boolean,
            voicemailCount: Int,
        ): VvmListState = when {
            !carrierConfigValid -> NoCarrierConfig
            !activated -> Activating
            networkRevoked -> NetworkRevoked
            imapError -> ImapError
            voicemailCount == 0 -> Empty
            else -> List(voicemailCount)
        }
    }
}