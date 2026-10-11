// SPDX-License-Identifier: AGPL-3.0-or-later
//
// "Calling out via": the numbers of the PBX an extension may call out with (a minimal read of `GET /me/extension`, which the own-extension
// feature reads in full) and the SIP header that tells the PBX which of them an outgoing call uses.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.Serializable

/** A number of the PBX as `GET /me/extension` lists it. */
@Serializable
data class OutboundNumber(
    val id: String,
    /** National, without country code (`0850607848`). */
    val number: String,
    val name: String? = null,
    /** The default number of the PBX. */
    val isDefault: Boolean = false,
)

/** The numbers an extension may call out with. Everything else in `GET /me/extension` is ignored here. */
@Serializable
data class OutboundNumbers(
    val numbers: List<OutboundNumber> = emptyList(),
    /** The number the extension calls out with when nothing is chosen. */
    val defaultNumber: String? = null,
)

/** `GET /me/extension`, only the numbers. */
suspend fun FsVoipApiClient.outboundNumbers(): OutboundNumbers = get("me/extension", OutboundNumbers.serializer())

interface OutboundNumbersServicing {
    suspend fun numbers(account: StoredAccount): OutboundNumbers
}

class LiveOutboundNumbersService(private val api: FsVoipApiClient = FsVoipApiClient()) : OutboundNumbersServicing {
    override suspend fun numbers(account: StoredAccount): OutboundNumbers = api.authenticated(account.deviceToken).outboundNumbers()
}

/**
 * The SIP header `X-FSS-From`. 🚨 Only sent when `AppCapabilities.callerChoice` is `true`: a PBX without the dialplan would forward an
 * unknown header to the provider.
 */
object CallerChoice {
    const val HEADER_NAME = "X-FSS-From"

    /** A national number of the PBX: ten digits, the first a 0 and the second not (`0850607848`). */
    fun isValidNumber(number: String): Boolean =
        number.length == 10 && number.all { it in '0'..'9' } && number[0] == '0' && number[1] != '0'

    /** Emergency numbers are never touched by the choice (the PBX does not either). */
    fun appliesTo(dialled: String): Boolean = dialled.trim() !in EMERGENCY_NUMBERS

    /**
     * The SIP headers to add to an outgoing INVITE. Empty unless the PBX supports the choice AND [number] is a number it lists.
     * [numbers] = the numbers `GET /me/extension` listed.
     */
    fun headers(choosing: String?, capabilities: AppCapabilities?, numbers: List<OutboundNumber>): Map<String, String> {
        if (capabilities?.callerChoice != true || choosing == null || !isValidNumber(choosing) || numbers.none { it.number == choosing }) {
            return emptyMap()
        }

        return mapOf(HEADER_NAME to choosing)
    }

    private val EMERGENCY_NUMBERS = setOf("112", "911", "999")
}
