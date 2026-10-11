// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIndexTest {
    private fun entry(id: String, name: String, numbers: List<String>, source: ContactSource = ContactSource.Customer) = ContactEntry.withNumbers(id, name, numbers, source)

    @Test
    fun findsTheNameInEveryWrittenForm() {
        val index = ContactIndex(listOf(entry("1", "Pieter de Groot", listOf("+31612345678"))))

        for (form in listOf("0612345678", "+31612345678", "0031612345678", "06 12 34 56 78", "31612345678")) {
            assertEquals(form, "Pieter de Groot", index.name(form))
        }
    }

    @Test
    fun aStoredNationalNumberMatchesAnInternationalCaller() {
        val index = ContactIndex(listOf(entry("1", "Henk", listOf("06-12345678"))))

        assertEquals("Henk", index.name("+31612345678"))
    }

    @Test
    fun extensionsMatchOnlyExactly() {
        val index = ContactIndex(listOf(entry("1", "Receptie", listOf("100"), ContactSource.InternalExtensions), entry("2", "Pieter", listOf("+31612345678"))))

        assertEquals("Receptie", index.name("100"))
        assertNull(index.name("10"))
        assertNull(index.name("1000"))
        assertNull(index.name("0100"))
    }

    @Test
    fun shortNumbersGiveNoFalsePositives() {
        val index = ContactIndex(listOf(entry("1", "Pieter", listOf("+31612345678")), entry("2", "Anja", listOf("+31701234567"))))

        for (number in listOf("112", "101", "1234", "5678", "345678", "12345678", "*97")) assertNull(number, index.name(number))
    }

    @Test
    fun theLastNineDigitsAreAFallbackOnlyWhenItIsUnambiguous() {
        // The number was stored without a usable country form; the caller comes in with a prefix the exact form misses.
        val single = ContactIndex(listOf(entry("1", "Jan", listOf("612345678"))))
        assertEquals("Jan", single.name("0612345678"))
        assertEquals("Jan", single.name("+31612345678"))

        val two = ContactIndex(listOf(entry("1", "Jan", listOf("612345678")), entry("2", "Kees", listOf("+49612345678"))))
        assertNull("two different people share these digits: unknown beats wrong", two.name("0612345678"))
    }

    @Test
    fun aCallerThatStatesItsCountryDoesNotFallBackOnAnotherCountry() {
        val index = ContactIndex(listOf(entry("1", "Pieter", listOf("+31471234567"))))

        assertNull(index.name("+32471234567"))
        assertEquals("Pieter", index.name("0471234567"))
    }

    @Test
    fun theSamePersonInTwoSourcesDoesNotBlockTheFallback() {
        val index = ContactIndex(listOf(entry("1", "Pieter de Groot", listOf("612345678")), entry("2", "pieter de groot", listOf("0612345670"), ContactSource.Device)))

        assertEquals("Pieter de Groot", index.name("+31612345678"))
    }

    @Test
    fun theFirstEntryWinsForAnExactMatch() {
        val index = ContactIndex(listOf(entry("1", "Klantcontact", listOf("+31612345678")), entry("2", "Telefooncontact", listOf("0612345678"), ContactSource.Device)))

        assertEquals("Klantcontact", index.name("0612345678"))
    }

    @Test
    fun lookupIsFastForAHugeAddressBook() {
        val entries = (0 until 25_000).map { number -> entry("c$number", "Contact $number", listOf("+316" + number.toString().padStart(8, '0'))) }
        val index = ContactIndex(entries)
        val start = System.nanoTime()

        repeat(200) {
            assertEquals("Contact 12345", index.name("0600012345"))
            assertNull(index.name("0700012345"))
        }

        val perLookup = (System.nanoTime() - start) / 1e9 / 400
        assertTrue("a name must be there far inside the 50 ms the call screen allows ($perLookup s)", perLookup < 0.005)
    }
}
