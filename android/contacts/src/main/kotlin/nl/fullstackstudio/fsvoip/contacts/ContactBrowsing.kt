// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

/** Which contacts the Contacts tab shows. */
sealed interface ContactFilter {
    data object All : ContactFilter

    /** Only the customer's address book. */
    data object Customer : ContactFilter

    /** The members of one list of one account. */
    data class ListMembers(val accountId: String, val listId: String) : ContactFilter

    data object Device : ContactFilter

    data object Colleagues : ContactFilter
}

data class ContactSection(val letter: Char, val entries: List<ContactEntry>)

object ContactBrowsing {
    /**
     * Filter, search and group by first letter (digits and symbols under `#`, last). Sorted case- and accent-insensitively.
     *
     * [members] answers "which customer-contact ids are in this list"; it is only called for a list filter.
     */
    fun sections(entries: List<ContactEntry>, filter: ContactFilter, query: String, members: (accountId: String, listId: String) -> Set<String>): List<ContactSection> {
        val allowed = (filter as? ContactFilter.ListMembers)?.let { members(it.accountId, it.listId) }

        val selected = entries.filter { entry ->
            val inFilter = when (filter) {
                ContactFilter.All -> true
                ContactFilter.Customer -> entry.source == ContactSource.Customer
                ContactFilter.Device -> entry.source == ContactSource.Device
                ContactFilter.Colleagues -> entry.source == ContactSource.InternalExtensions
                is ContactFilter.ListMembers -> entry.source == ContactSource.Customer && entry.contactId != null && allowed?.contains(entry.contactId) == true
            }

            inFilter && entry.matches(query)
        }

        return selected.groupBy { it.sectionLetter }
            .map { (letter, group) -> ContactSection(letter, group.sortedWith { a, b -> ContactText.nameOrder.compare(a.displayName, b.displayName) }) }
            .sortedWith { a, b ->
                when {
                    a.letter == '#' -> 1
                    b.letter == '#' -> -1
                    else -> a.letter.compareTo(b.letter)
                }
            }
    }

    /** Up to two letters for the round avatar ("PG" for Pieter de Groot, "B" for Bakkerij Smit). */
    fun initials(name: String): String {
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetter() }
        val first = words.firstOrNull()?.first() ?: return "#"

        if (words.size < 2) return first.toString().uppercase()

        return (first.toString() + words.last().first()).uppercase()
    }
}
