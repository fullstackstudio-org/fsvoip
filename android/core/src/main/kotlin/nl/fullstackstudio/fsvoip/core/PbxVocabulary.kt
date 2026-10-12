// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Small pure helpers that turn PBX data into the words and shapes the screens need (the Android side of iOS `PbxVocabulary`).

package nl.fullstackstudio.fsvoip.core

object PbxVocabulary {
    /** `0850607848` -> `085 060 7848`; anything else (not exactly ten digits) stays as it is. */
    fun formatNumber(number: String): String {
        if (number.length != 10 || !number.all { it in '0'..'9' }) {
            return number
        }

        return "${number.substring(0, 3)} ${number.substring(3, 6)} ${number.substring(6)}"
    }

    /** "Hoofdnummer · 085 060 7848", or just the number when it has no name. */
    fun numberTitle(number: SelfNumber): String {
        val formatted = formatNumber(number.number)
        val name = number.name?.trim().orEmpty()

        return if (name.isEmpty()) formatted else "$name · $formatted"
    }

    /**
     * The option a stored target corresponds to, or `null` (no target, an external number, or an object that does not exist any more).
     * Matches on type and id.
     */
    fun optionFor(target: PbxTarget?, options: List<PbxTargetOption>): PbxTargetOption? {
        if (target == null || target.type == TargetType.EXTERNAL || target.id == null) {
            return null
        }

        return options.firstOrNull { it.type == target.type && it.id == target.id }
    }

    /** The words for a destination: the name of the object, the external number, or [none] / [gone]. */
    fun targetLabel(target: PbxTarget?, options: List<PbxTargetOption>, none: String, gone: String): String = when {
        target == null -> none
        target.type == TargetType.EXTERNAL -> target.number?.takeIf { it.isNotBlank() } ?: gone
        target.type == TargetType.HANGUP -> none
        else -> optionFor(target, options)?.let { option -> option.extensionNumber?.let { "${option.name} · $it" } ?: option.name } ?: gone
    }

    /** The destination an option stands for. An external option needs the number the user typed; a bad one gives `null`. */
    fun targetFor(option: PbxTargetOption, externalNumber: String? = null): PbxTarget? = when (option.type) {
        TargetType.EXTERNAL -> normalizeExternalNumber(externalNumber)?.let { PbxTarget.external(it) }
        TargetType.UNKNOWN -> null
        else -> option.id?.let { PbxTarget.obj(option.type, it) }
    }

    /**
     * An external phone number as the PBX wants it: digits with an optional leading `+` (or `00`), spaces, dashes and brackets
     * removed. `null` when it is not plausible (fewer than 6 or more than 15 digits).
     */
    fun normalizeExternalNumber(input: String?): String? {
        val cleaned = input.orEmpty().trim().filter { it.isDigit() || it == '+' }
        val plus = cleaned.startsWith("+")
        val digits = cleaned.filter { it.isDigit() }

        if (cleaned.drop(1).contains('+') || digits.length !in 6..15) {
            return null
        }

        return if (plus) "+$digits" else digits
    }
}
