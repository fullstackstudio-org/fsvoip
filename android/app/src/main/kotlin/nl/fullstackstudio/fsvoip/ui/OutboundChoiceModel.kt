// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.fullstackstudio.fsvoip.core.AppCapabilities
import nl.fullstackstudio.fsvoip.core.CallerChoice
import nl.fullstackstudio.fsvoip.core.OutboundNumber
import nl.fullstackstudio.fsvoip.core.OutboundNumbers
import nl.fullstackstudio.fsvoip.core.OutboundNumbersServicing
import nl.fullstackstudio.fsvoip.core.PreferencesStore
import nl.fullstackstudio.fsvoip.core.StoredAccount
import nl.fullstackstudio.fsvoip.sipengine.CallOptions

/**
 * "Bellen via": which number of the PBX the next call goes out with (plan `fsvoip-app-v2`, D5).
 *
 * - The choice is local, per account (`AccountPreferences.outboundNumber`). Switching it never touches the network.
 * - It only exists when the server says the PBX understands `X-FSS-From` (`AppCapabilities.callerChoice`). Without that there is no
 *   chooser, no chevron and no header: the call goes out with the number of the extension.
 * - It becomes [CallOptions.fromNumber] of the NEXT outgoing call, and of nothing else.
 */
class OutboundChoiceModel(
    private val service: OutboundNumbersServicing?,
    private val preferences: PreferencesStore,
    /** What the server said this pairing may do. Wired by the app model. */
    var capabilities: (accountId: String) -> AppCapabilities? = { null },
) {
    private val _loaded = MutableStateFlow<Map<String, OutboundNumbers>>(emptyMap())
    private val _revision = MutableStateFlow(0)
    private val loading = mutableSetOf<String>()

    /** Observe this (or [revision]) to redraw when the numbers or the choice change. */
    val loaded: StateFlow<Map<String, OutboundNumbers>> = _loaded.asStateFlow()
    val revision: StateFlow<Int> = _revision.asStateFlow()

    // MARK: Reading

    fun offersChoice(accountId: String): Boolean = capabilities(accountId)?.callerChoice == true

    fun numbers(accountId: String): List<OutboundNumber> = if (offersChoice(accountId)) _loaded.value[accountId]?.numbers.orEmpty() else emptyList()

    /** The chooser is only worth opening with something to choose from. */
    fun canChoose(accountId: String): Boolean = numbers(accountId).size > 1

    /** What the user chose and the PBX still lists. */
    private fun chosen(accountId: String): String? =
        preferences.preferences(accountId).outboundNumber?.takeIf { stored -> numbers(accountId).any { it.number == stored } }

    /** The number shown as selected: the choice, else the default of the extension, else none. */
    fun selected(accountId: String): OutboundNumber? {
        val list = numbers(accountId)

        chosen(accountId)?.let { chosen -> list.firstOrNull { it.number == chosen }?.let { return it } }

        val defaultNumber = _loaded.value[accountId]?.defaultNumber

        return list.firstOrNull { it.number == defaultNumber } ?: list.firstOrNull { it.isDefault }
    }

    /** "Hoofdnummer · 085 060 7848", or [defaultLabel] ("Standaardnummer") when there is nothing to show. */
    fun label(accountId: String, defaultLabel: String): String = selected(accountId)?.let(::title) ?: defaultLabel

    // MARK: Choosing and calling

    /** Remember the choice. No request: the number rides along with the next call. */
    fun select(number: String, accountId: String) {
        if (numbers(accountId).none { it.number == number }) return

        preferences.setPreferences(preferences.preferences(accountId).copy(outboundNumber = number), accountId)
        _revision.value++
    }

    /**
     * The options of the next outgoing call of this account. Empty unless the PBX supports the choice AND the user chose a number
     * it lists.
     */
    fun options(accountId: String): CallOptions {
        val headers = CallerChoice.headers(chosen(accountId), capabilities(accountId), _loaded.value[accountId]?.numbers.orEmpty())

        return CallOptions(fromNumber = headers[CallerChoice.HEADER_NAME])
    }

    // MARK: Loading

    suspend fun load(account: StoredAccount) {
        val service = service ?: return

        if (!offersChoice(account.id) || !loading.add(account.id)) return

        try {
            // A failure keeps what is known: the chooser then simply stays as it was.
            val result = try {
                service.numbers(account)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return
            }

            _loaded.value = _loaded.value + (account.id to result)
        } finally {
            loading.remove(account.id)
        }
    }

    fun forget(accountId: String) {
        _loaded.value = _loaded.value - accountId
    }

    companion object {
        fun title(number: OutboundNumber): String {
            val formatted = formatNumber(number.number)
            val name = number.name?.trim().orEmpty()

            return if (name.isEmpty()) formatted else "$name · $formatted"
        }

        /** "085 060 7848" for a national ten-digit number; anything else as it is. */
        fun formatNumber(number: String): String {
            if (number.length != 10 || !number.all { it in '0'..'9' }) return number

            return "${number.take(3)} ${number.drop(3).take(3)} ${number.drop(6)}"
        }
    }
}
