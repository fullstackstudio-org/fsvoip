// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

/** What the Contacten tab shows of the address book. */
sealed interface ContactsFilter {
    data object All : ContactsFilter

    /** Only the address book of the customer (the portal's contacts). */
    data object AddressBook : ContactsFilter

    /** Only the favourites of this phone (they never leave the phone). */
    data object Favorites : ContactsFilter

    /** Only the colleagues of the PBX. */
    data object Colleagues : ContactsFilter

    /** The members of one contact list of one account. */
    data class OfList(val accountId: String, val listId: String) : ContactsFilter
}

object ContactsBrowsing {
    /**
     * The entries to show for [filter] and [query], sorted by name. The query matches the name, the company and the digits of the
     * numbers (`0612` finds `+31 6 12 ...`).
     */
    fun visible(entries: List<ContactEntry>, filter: ContactsFilter, query: String, hub: ContactsHub, favorites: Set<String> = emptySet()): List<ContactEntry> {
        val members = (filter as? ContactsFilter.OfList)?.let { hub.members(it.accountId, it.listId) }

        val filtered = entries.filter { entry ->
            when (filter) {
                ContactsFilter.All -> true
                ContactsFilter.Favorites -> entry.id in favorites
                ContactsFilter.AddressBook -> entry.source == ContactSource.AddressBook
                ContactsFilter.Colleagues -> entry.source == ContactSource.InternalExtensions
                is ContactsFilter.OfList -> entry.accountId == filter.accountId && entry.id.substringAfter('/') in members.orEmpty()
            }
        }

        return search(filtered, query).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.phones.firstOrNull()?.number.orEmpty() } })
    }

    fun search(entries: List<ContactEntry>, query: String): List<ContactEntry> {
        val text = query.trim().lowercase()

        if (text.isEmpty()) return entries

        val digits = text.filter { it in '0'..'9' }

        return entries.filter { entry ->
            entry.name.lowercase().contains(text) ||
                entry.company.orEmpty().lowercase().contains(text) ||
                (digits.length >= 2 && entry.phones.any { phone -> phone.number.filter { it in '0'..'9' }.contains(digits) })
        }
    }
}
