package com.piercingxx.xxphone.util

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * E.164 normalization on libphonenumber (design §6). The property under test:
 * `+1 415 555 0100`, `4155550100`, and `0014155550100` are ONE caller.
 * Garbage normalizes to null, which classifies the caller as unknown — noisy,
 * never lossy (§15).
 */
object E164 {

    private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    fun normalize(raw: String?, defaultRegion: String = "US"): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val parsed = util.parse(liftDoubleZeroPrefix(raw), defaultRegion)
            if (util.isValidNumber(parsed)) util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            else null
        } catch (_: NumberParseException) {
            null
        }
    }

    // "00" is the international dialing prefix on most non-NANP networks, but
    // libphonenumber only strips the default region's own prefix ("011" for
    // US). Lift a leading 00 to "+" so 00-prefixed forms parse internationally.
    private fun liftDoubleZeroPrefix(raw: String): String =
        if (raw.startsWith("00") && !raw.startsWith("+")) "+${raw.substring(2)}" else raw
}
