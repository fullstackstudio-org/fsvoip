// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

/**
 * Makes phone numbers comparable: `06 12 34 56 78`, `+31612345678`, `0031612345678`, `31612345678` and `sip:0612345678@x` are the same
 * number. Short numbers (extensions such as `101`, `112`, `*97`) are never turned into something longer and only match exactly.
 */
object PhoneNumberMatcher {
    /** The country code a number without one is assumed to belong to (the Netherlands). */
    const val DEFAULT_COUNTRY_CODE = "31"

    data class Key(
        /** The canonical form: `+31612345678`, or the bare digits of a short number (`101`). */
        val exact: String,
        /** The last 9 digits, for the fallback when two forms of one number do not normalise alike (`null` for short numbers). */
        val tail: String?,
        /** The country code was assumed (the number came in national form, `0612345678`), not stated (`+32...`). */
        val isCountryAssumed: Boolean,
    )

    private fun isDigit(char: Char) = char in '0'..'9'

    /** `null` when the text holds no digits. */
    fun key(raw: String, countryCode: String = DEFAULT_COUNTRY_CODE): Key? {
        var text = raw.trim()

        // `sip:0612345678@domain`, `tel:+31612345678;ext=1`
        for (prefix in listOf("sips:", "sip:", "tel:")) {
            if (text.lowercase().startsWith(prefix)) text = text.drop(prefix.length)
        }

        text.indexOf('@').takeIf { it >= 0 }?.let { text = text.substring(0, it) }
        text.indexOf(';').takeIf { it >= 0 }?.let { text = text.substring(0, it) }

        text = text.replace("(0)", "")

        val hasPlus = text.startsWith("+")
        val digits = text.filter(::isDigit)

        if (digits.isEmpty()) return null

        // Dialling codes with * and # (`*97`, `#31#`): not a phone number, compare as typed.
        if (text.any { it == '*' || it == '#' }) {
            return Key(exact = text.filter { isDigit(it) || it == '*' || it == '#' }, tail = null, isCountryAssumed = false)
        }

        var international: String? = null
        var assumed = false

        when {
            hasPlus -> international = digits
            digits.startsWith("00") && digits.length > 4 -> international = digits.drop(2)
            digits.startsWith("0") && digits.length >= 9 -> {
                international = countryCode + digits.drop(1)
                assumed = true
            }
            digits.startsWith(countryCode) && digits.length >= 11 -> international = digits
        }

        var number = international
            // A short number or an extension: exact digits only, and a tail only when it is long enough to be a subscriber number.
            ?: return Key(exact = digits, tail = if (digits.length >= 9) digits.takeLast(9) else null, isCountryAssumed = true)

        // `+31 0 70 ...`: the trunk zero does not belong after the country code.
        if (number.startsWith(countryCode + "0") && number.length >= countryCode.length + 10) {
            number = countryCode + number.drop(countryCode.length + 1)
        }

        return Key(exact = "+$number", tail = if (number.length >= 9) number.takeLast(9) else null, isCountryAssumed = assumed)
    }

    /** The `+...` form of a number, or `null` when it is not a subscriber number (short numbers, codes). Used before sending a number to the server. */
    fun e164(raw: String, countryCode: String = DEFAULT_COUNTRY_CODE): String? {
        val key = key(raw, countryCode) ?: return null

        return key.exact.takeIf { it.startsWith("+") }
    }

    /** Whether two numbers are the same number (exact match of the canonical form). */
    fun same(left: String, right: String): Boolean {
        val a = key(left) ?: return false
        val b = key(right) ?: return false

        return a.exact == b.exact
    }
}
