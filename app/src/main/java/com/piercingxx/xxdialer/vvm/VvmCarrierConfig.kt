package com.piercingxx.xxdialer.vvm

/**
 * Whether a carrier bundle is a usable VVM config (todo.md D5): a non-blank
 * `KEY_VVM_TYPE_STRING`. Pure so the voicemail tab can fail toward
 * [VvmListState.NoCarrierConfig] without inventing IMAP credentials.
 */
object VvmCarrierConfig {

    fun isValid(typeString: String?): Boolean = !typeString.isNullOrBlank()
}
