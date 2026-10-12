// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The country code for a number typed in national form. Mirror of the iOS `PhoneCountry`: the Netherlands comes first; a number that
// carries its own code (`+32...`, `0032...`) shows that country; another country turns `0612...` into `+32612...`.

package nl.fullstackstudio.fsvoip.contacts

data class PhoneCountry(val region: String, val dialCode: String) {
    val dialText: String get() = "+$dialCode"

    /**
     * The number as it goes to the server. A number that carries its own code, and any Dutch number, stays as typed (the server side
     * already reads Dutch national numbers); another country turns `0612...` into `+32612...`.
     */
    fun apply(number: String): String {
        val typed = number.trim()

        if (typed.isEmpty() || this == NETHERLANDS || typed.startsWith("+")) return typed

        if (typed.startsWith("00")) return "+" + typed.drop(2)

        val national = if (typed.startsWith("0")) typed.drop(1) else typed

        return "+" + dialCode + national.filter { it.isDigit() || it == ' ' }
    }

    companion object {
        val NETHERLANDS = PhoneCountry("NL", "31")

        private val others = listOf(
            "BE" to "32", "DE" to "49", "FR" to "33", "GB" to "44", "LU" to "352", "ES" to "34", "IT" to "39", "PT" to "351",
            "AT" to "43", "CH" to "41", "DK" to "45", "SE" to "46", "NO" to "47", "FI" to "358", "PL" to "48", "IE" to "353",
            "TR" to "90", "MA" to "212", "SR" to "597", "US" to "1",
        ).map { PhoneCountry(it.first, it.second) }

        /** The Netherlands first, then the rest by name (in the locale of the phone). */
        fun all(name: (PhoneCountry) -> String = { it.region }): List<PhoneCountry> =
            listOf(NETHERLANDS) + others.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { name(it) })

        fun forRegion(region: String?): PhoneCountry? = (listOf(NETHERLANDS) + others).firstOrNull { it.region == region }

        /** The country of a number that already carries its code (`+32...` or `0032...`); `null` for a national number. */
        fun detect(number: String): PhoneCountry? {
            val trimmed = number.trim()
            val digits = when {
                trimmed.startsWith("+") -> trimmed.drop(1).filter { it.isDigit() }
                trimmed.startsWith("00") -> trimmed.drop(2).filter { it.isDigit() }
                else -> return null
            }

            return (listOf(NETHERLANDS) + others).filter { digits.startsWith(it.dialCode) }.maxByOrNull { it.dialCode.length }
        }
    }
}
