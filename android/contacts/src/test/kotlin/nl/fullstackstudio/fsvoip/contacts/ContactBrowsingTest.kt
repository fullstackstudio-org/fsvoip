// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactBrowsingTest {
    private fun customer(contactId: String, name: String, numbers: List<String> = listOf("+31611111111"), company: String? = null, email: String? = null) =
        ContactEntry("customer:$contactId", name, company, email, numbers.map { ContactPhoneEntry(it) }, ContactSource.Customer, listOf("acc"), contactId)

    private val none: (String, String) -> Set<String> = { _, _ -> emptySet() }

    private fun names(sections: List<ContactSection>) = sections.flatMap { it.entries }.map { it.displayName }

    @Test
    fun groupsByLetterSortedWithSymbolsLast() {
        val entries = listOf(customer("1", "Bea"), customer("2", "anna"), customer("3", "Ärend"), customer("4", "06-bakker"), customer("5", "Anja"))

        val sections = ContactBrowsing.sections(entries, ContactFilter.All, "", none)

        assertEquals("Ärend files under A: accents do not matter", listOf('A', 'B', '#'), sections.map { it.letter })
        assertEquals(listOf("Anja", "anna", "Ärend"), sections[0].entries.map { it.displayName })
    }

    @Test
    fun searchFindsNameCompanyEmailAndPartsOfANumber() {
        val entries = listOf(
            customer("1", "Pieter de Groot", listOf("+31612345678")),
            customer("2", "Anna", company = "Bakkerij Smit"),
            customer("3", "Cor", email = "cor@voorbeeld.nl"),
        )

        fun found(query: String) = names(ContactBrowsing.sections(entries, ContactFilter.All, query, none))

        assertEquals(listOf("Pieter de Groot"), found("groot"))
        assertEquals(listOf("Anna"), found("bakkerij"))
        assertEquals(listOf("Cor"), found("VOORBEELD"))
        assertEquals(listOf("Pieter de Groot"), found("06 12 34"))
        assertEquals(listOf("Pieter de Groot"), found("123456"))
        assertEquals(emptyList<String>(), found("zzz"))
        assertEquals(listOf("Anna", "Cor", "Pieter de Groot"), found("  "))
    }

    @Test
    fun searchIgnoresAccents() {
        val entries = listOf(customer("1", "Zoë Smit"))

        assertEquals(listOf("Zoë Smit"), names(ContactBrowsing.sections(entries, ContactFilter.All, "zoe", none)))
        assertEquals(listOf("Zoë Smit"), names(ContactBrowsing.sections(entries, ContactFilter.All, "ZOË", none)))
    }

    @Test
    fun twoCharactersOfDigitsDoNotMatchEverything() {
        val entries = listOf(customer("1", "Pieter", listOf("+31612345678")))

        assertTrue(ContactBrowsing.sections(entries, ContactFilter.All, "12", none).isEmpty())
    }

    @Test
    fun filtersBySource() {
        val entries = listOf(
            customer("1", "Anna"),
            ContactEntry.withNumbers("device:1", "Mama", listOf("0655555555"), ContactSource.Device),
            ContactEntry.withNumbers("internal:1", "Receptie", listOf("100"), ContactSource.InternalExtensions),
        )

        fun names(filter: ContactFilter) = ContactBrowsing.sections(entries, filter, "", none).flatMap { it.entries }.map { it.name }

        assertEquals(listOf("Anna", "Mama", "Receptie"), names(ContactFilter.All).sorted())
        assertEquals(listOf("Anna"), names(ContactFilter.Customer))
        assertEquals(listOf("Mama"), names(ContactFilter.Device))
        assertEquals(listOf("Receptie"), names(ContactFilter.Colleagues))
    }

    @Test
    fun aListFilterShowsOnlyItsMembers() {
        val entries = listOf(customer("1", "Anna"), customer("2", "Bea"), ContactEntry.withNumbers("device:1", "Mama", listOf("0655555555"), ContactSource.Device))

        val sections = ContactBrowsing.sections(entries, ContactFilter.ListMembers("acc", "l1"), "") { account, list -> if (account == "acc" && list == "l1") setOf("2") else emptySet() }

        assertEquals(listOf("Bea"), sections.flatMap { it.entries }.map { it.name })
    }

    @Test
    fun initials() {
        assertEquals("PG", ContactBrowsing.initials("Pieter de Groot"))
        assertEquals("BS", ContactBrowsing.initials("Bakkerij Smit"))
        assertEquals("A", ContactBrowsing.initials("anna"))
        assertEquals("#", ContactBrowsing.initials("06123"))
        assertEquals("#", ContactBrowsing.initials(""))
    }

    @Test
    fun theDisplayNameFallsBackOnTheCompanyAndThenTheNumber() {
        assertEquals("Smit", ContactEntry("1", " ", "Smit", null, emptyList(), ContactSource.Customer).displayName)
        assertEquals("0612", ContactEntry.withNumbers("1", "", listOf("0612"), ContactSource.Device).displayName)
    }
}
