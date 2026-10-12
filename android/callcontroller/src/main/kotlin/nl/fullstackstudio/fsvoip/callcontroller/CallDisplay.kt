// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.callcontroller

/**
 * What the call screens show (plan D10). On Android the incoming call screen is our own and shows caller and account
 * apart; this combined text is what Telecom (car, watch, Bluetooth) and the notification title get.
 */
object CallDisplay {
    /** The caller: name, otherwise number, otherwise the anonymous text. */
    fun callerTitle(callerName: String?, callerNumber: String?, anonymous: String = "Onbekend"): String =
        callerName?.trim()?.takeIf { it.isNotEmpty() } ?: callerNumber?.trim()?.takeIf { it.isNotEmpty() } ?: anonymous

    /** Standard: `<caller>`. With "show dialled account": `<caller> → <account label>`. */
    fun callerText(callerName: String?, callerNumber: String?, accountLabel: String, showAccount: Boolean, anonymous: String = "Onbekend"): String {
        val caller = callerTitle(callerName, callerNumber, anonymous)

        return if (showAccount && accountLabel.isNotEmpty()) "$caller → $accountLabel" else caller
    }

    /** The per-account setting decides; when never set, the account is shown as soon as several accounts are paired. */
    fun shouldShowAccount(setting: Boolean?, accountCount: Int): Boolean = setting ?: (accountCount > 1)
}

/** The number a user typed or pasted, cleaned for dialling. */
object DialNumber {
    const val MAX_LENGTH = 32

    /**
     * Keeps digits, `*`, `#` and a leading `+`; drops spaces, dashes, dots, brackets and anything else (a pasted
     * "+31 (0)70-123 45 67" becomes "+31701234567": the "(0)" trunk prefix is removed too). Unicode digits (full-width,
     * Arabic-Indic) become ASCII.
     */
    fun sanitize(input: String): String {
        val text = input.replace("(0)", "").replace("（0）", "")
        val result = StringBuilder()

        for (character in text) {
            val ascii = when {
                character in '0'..'9' || character == '*' || character == '#' -> character
                Character.isDigit(character) -> ('0' + Character.getNumericValue(character)).takeIf { Character.getNumericValue(character) in 0..9 }
                character == '＊' -> '*'
                character == '＃' -> '#'
                character == '+' || character == '＋' -> if (result.isEmpty()) '+' else null
                else -> null
            }

            ascii?.let(result::append)
        }

        return result.toString().take(MAX_LENGTH)
    }

    /** Digits, `+` (first only), `*`, `#`, 1 to 32 characters. Anything else could smuggle SIP syntax into the request URI. */
    fun isDialable(number: String): Boolean {
        if (number.length !in 1..MAX_LENGTH || number == "+") {
            return false
        }

        return number.withIndex().all { (index, character) ->
            character in '0'..'9' || character == '*' || character == '#' || (character == '+' && index == 0)
        }
    }
}
