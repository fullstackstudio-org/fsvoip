// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The pure parts of the Contacts tab (the iOS `ContactsSupport` / `ContactsBrowsing` of the UI package): segment, filter, the A-Z
// strip, favourites, the edit rules and the failure reasons. No Compose here, so they are unit tested.

package nl.fullstackstudio.fsvoip.contacts

import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.KeyValueStore
import nl.fullstackstudio.fsvoip.core.RecentCall

/** The two halves of the Contacts tab: what the phone system knows, and what is on this phone. */
enum class ContactScope { CENTRALE, PHONE }

/** The filter behind "Alles". Favourites are local to this phone. */
sealed interface ContactListFilter {
    data object All : ContactListFilter

    data object Favorites : ContactListFilter

    data object InternalOnly : ContactListFilter

    data class List(val accountId: String, val listId: String) : ContactListFilter
}

/** Segment, filter, search and grouping in one pure function, on top of the unchanged [ContactBrowsing]. */
object ContactsScreenBrowsing {
    fun sections(
        entries: kotlin.collections.List<ContactEntry>,
        scope: ContactScope,
        filter: ContactListFilter,
        query: String,
        favorites: Set<String>,
        members: (accountId: String, listId: String) -> Set<String>,
    ): kotlin.collections.List<ContactSection> {
        var scoped = entries.filter { if (scope == ContactScope.PHONE) it.source == ContactSource.Device else it.source != ContactSource.Device }

        val base = when (filter) {
            ContactListFilter.All -> ContactFilter.All
            ContactListFilter.Favorites -> {
                scoped = scoped.filter { it.id in favorites }
                ContactFilter.All
            }
            ContactListFilter.InternalOnly -> ContactFilter.Colleagues
            is ContactListFilter.List -> ContactFilter.ListMembers(filter.accountId, filter.listId)
        }

        return ContactBrowsing.sections(scoped, base, query, members)
    }
}

/** The A-Z strip along the edge of the list. */
object AlphabetIndex {
    /** Every letter the strip shows, `#` last. */
    val letters: kotlin.collections.List<Char> = ('A'..'Z').toList() + '#'

    /** The section to scroll to for a letter: its own section, otherwise the next one that exists, otherwise the last. */
    fun target(letter: Char, sections: kotlin.collections.List<ContactSection>): Char? {
        val ids = sections.map { it.letter }

        if (letter in ids) return letter

        val position = letters.indexOf(letter)

        if (position < 0) return ids.lastOrNull()

        for (next in letters.drop(position)) if (next in ids) return next

        return ids.lastOrNull()
    }

    /** The letter under a finger at [fraction] (0 = top, 1 = bottom) of the strip. */
    fun letter(fraction: Double): Char {
        val clamped = fraction.coerceIn(0.0, 0.999_999)

        return letters[(clamped * letters.size).toInt()]
    }
}

/** The favourites of this phone: a set of contact ids kept in a local store. They never leave the phone. */
class FavoriteContacts(private val store: KeyValueStore, private val key: String = STORAGE_KEY) {
    private val _ids = kotlinx.coroutines.flow.MutableStateFlow(read())

    val ids: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = _ids

    private fun read(): Set<String> {
        val text = store.getString(key) ?: return emptySet()

        return runCatching { FsJson.default.decodeFromString(ListSerializer(String.serializer()), text).toSet() }.getOrDefault(emptySet())
    }

    fun contains(id: String): Boolean = id in _ids.value

    fun toggle(id: String) {
        val next = if (id in _ids.value) _ids.value - id else _ids.value + id

        _ids.value = next
        store.putString(key, FsJson.default.encodeToString(ListSerializer(String.serializer()), next.sorted()))
    }

    companion object {
        const val STORAGE_KEY = "fsvoip.contacts.favorites"
    }
}

/** A country code for a number typed in national form. The Netherlands comes first. */
data class PhoneCountry(val region: String, val dialCode: String) {
    val name: String get() = Locale("", region).getDisplayCountry(Locale.getDefault()).ifEmpty { region }

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

        return "+" + dialCode + national.filter { it in '0'..'9' || it == ' ' }
    }

    companion object {
        val NETHERLANDS = PhoneCountry("NL", "31")

        private val others = listOf(
            "BE" to "32", "DE" to "49", "FR" to "33", "GB" to "44", "LU" to "352", "ES" to "34", "IT" to "39", "PT" to "351",
            "AT" to "43", "CH" to "41", "DK" to "45", "SE" to "46", "NO" to "47", "FI" to "358", "PL" to "48", "IE" to "353",
            "TR" to "90", "MA" to "212", "SR" to "597", "US" to "1",
        ).map { PhoneCountry(it.first, it.second) }

        /** The Netherlands first, then the rest by name. */
        val all: kotlin.collections.List<PhoneCountry>
            get() = listOf(NETHERLANDS) + others.sortedWith { a, b -> ContactText.nameOrder.compare(a.name, b.name) }

        /** The country of a number that already carries its code (`+32...` or `0032...`); `null` for a national number. */
        fun detect(number: String): PhoneCountry? {
            val trimmed = number.trim()
            val digits = when {
                trimmed.startsWith("+") -> trimmed.drop(1).filter { it in '0'..'9' }
                trimmed.startsWith("00") -> trimmed.drop(2).filter { it in '0'..'9' }
                else -> return null
            }

            return (listOf(NETHERLANDS) + others).filter { digits.startsWith(it.dialCode) }.maxByOrNull { it.dialCode.length }
        }
    }
}

/** When a contact may be saved, and what is sent. */
object ContactEditRules {
    /**
     * A number is enough (the contact is then named after it); a name needs a number or an e-mail address, because the server
     * refuses a contact nobody can reach.
     */
    fun canSave(draft: ContactDraft): Boolean = draft.canSave || draft.filledPhones.isNotEmpty()

    /** The draft as it is saved: numbers with their country code, and the first number as the name when there is none. */
    fun prepared(draft: ContactDraft, countries: Map<String, PhoneCountry>): ContactDraft {
        var result = draft.copy(phones = draft.phones.map { it.copy(number = (countries[it.id] ?: PhoneCountry.NETHERLANDS).apply(it.number)) })

        if (result.displayName.isEmpty()) {
            result.filledPhones.firstOrNull()?.let { result = result.copy(firstName = it.number.trim()) }
        }

        return result
    }
}

/** The calls the phone remembers with a contact's numbers, newest first. */
object ContactRecentCalls {
    fun matching(calls: kotlin.collections.List<RecentCall>, phones: kotlin.collections.List<String>, limit: Int = 5): kotlin.collections.List<RecentCall> =
        calls
            .filter { call -> call.number.isNotEmpty() && phones.any { PhoneNumberMatcher.same(call.number, it) } }
            .sortedByDescending { it.startedAtMillis }
            .take(limit)
}

/** What to tell the user when a contact request fails. Never the server's own words. */
enum class ContactFailure {
    PHONE,
    EMAIL,
    FORBIDDEN,
    LIMIT,
    RATE,
    NETWORK,
    GONE,
    STALE,
    GENERIC,
    ;

    companion object {
        fun of(error: Throwable): ContactFailure = when (error) {
            is ApiException.Invalid -> when (error.code) {
                "invalid_phone" -> PHONE
                "invalid_email" -> EMAIL
                else -> GENERIC
            }
            is ApiException.Forbidden -> FORBIDDEN
            is ApiException.Conflict -> if (error.code == "limit_reached") LIMIT else GENERIC
            is ApiException.RateLimited -> RATE
            is ApiException.Unavailable, is ApiException.Transport -> NETWORK
            is ApiException.NotFound -> GONE
            is ApiException.Stale -> STALE
            else -> GENERIC
        }
    }
}

/** An icon per timeline kind the server may send; an unknown kind gets a neutral dot. */
enum class TimelineSymbol {
    ORDER,
    TICKET,
    CALL,
    APPOINTMENT,
    FORM,
    MAIL,
    SMS,
    INVOICE,
    OTHER,
    ;

    companion object {
        fun of(kind: String): TimelineSymbol {
            val key = kind.lowercase()

            return when {
                key.contains("order") -> ORDER
                key.contains("ticket") || key.contains("request") -> TICKET
                key.contains("call") || key.contains("voice") -> CALL
                key.contains("appointment") -> APPOINTMENT
                key.contains("form") || key.contains("submission") -> FORM
                key.contains("mail") || key.contains("email") -> MAIL
                key.contains("sms") -> SMS
                key.contains("invoice") || key.contains("payment") -> INVOICE
                else -> OTHER
            }
        }
    }
}
