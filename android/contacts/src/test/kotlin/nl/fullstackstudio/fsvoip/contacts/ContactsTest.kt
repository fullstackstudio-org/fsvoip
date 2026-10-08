// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.InternalContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {
    @Test
    fun internalContacts() {
        val provider = InternalContactsProvider(listOf(InternalContact("100", "Receptie"), InternalContact("101", "Pieter")))
        assertEquals("Receptie", provider.name("100"))
        assertNull(provider.name("1000"))
        assertEquals(ContactSource.InternalExtensions, provider.entries.first().source)
    }

    @Test
    fun compositeTakesTheFirstName() {
        val lookup = CompositeNameLookup { listOf(NameLookup { null }, NameLookup { "Second" }, NameLookup { "Third" }) }
        assertEquals("Second", lookup.name("0612345678"))
    }

    @Test
    fun numberMatching() {
        assertTrue(PhoneNumbers.same("+31612345678", "06 12 34 56 78"))
        assertTrue(PhoneNumbers.same("100", "100"))
        assertFalse(PhoneNumbers.same("100", "1000"))
        assertFalse(PhoneNumbers.same("", "100"))
    }
}
