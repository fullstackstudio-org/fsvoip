// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import nl.fullstackstudio.fsvoip.callcontroller.DialNumber

/** The number being typed on the keypad (the same rules as iOS `DialerInput`). */
data class DialerInput(val number: String = "") {
    val isEmpty: Boolean
        get() = number.isEmpty()

    val canCall: Boolean
        get() = DialNumber.isDialable(number)

    /** A key press: digits, `*`, `#`; `+` only as the first character. */
    fun press(key: Char): DialerInput {
        if (number.length >= DialNumber.MAX_LENGTH) return this
        if (key == '+') return if (number.isEmpty()) copy(number = "+") else this
        if (key !in '0'..'9' && key != '*' && key != '#') return this

        return copy(number = number + key)
    }

    /** Long press on 0: `+` at the start, otherwise a 0. */
    fun longPressZero(): DialerInput = if (number.isEmpty()) copy(number = "+") else press('0')

    fun deleteLast(): DialerInput = copy(number = number.dropLast(1))

    fun clear(): DialerInput = copy(number = "")

    /** Paste replaces what was typed with the cleaned text. */
    fun paste(text: String): DialerInput = copy(number = DialNumber.sanitize(text))

    companion object {
        /** Letters under the keypad digits. */
        fun letters(key: Char): String = when (key) {
            '2' -> "ABC"
            '3' -> "DEF"
            '4' -> "GHI"
            '5' -> "JKL"
            '6' -> "MNO"
            '7' -> "PQRS"
            '8' -> "TUV"
            '9' -> "WXYZ"
            '0' -> "+"
            else -> ""
        }
    }
}
