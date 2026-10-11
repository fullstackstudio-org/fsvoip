// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.InMemoryKeyValueStore
import nl.fullstackstudio.fsvoip.core.RecentCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsScreenSupportTest {
    private fun entry(id: String, name: String, source: ContactSource = ContactSource.Customer, numbers: List<String> = listOf("0612345678")) =
        ContactEntry(id, name, null, null, numbers.map { ContactPhoneEntry(it) }, source, contactId = if (source == ContactSource.Customer) id else null)

    private val entries = listOf(
        entry("a", "Anna Bakker"),
        entry("b", "Bram Visser"),
        entry("m", "Mila de Jong"),
        entry("p", "Pieter de Groot"),
        entry("z", "Zoë Smit"),
        entry("i1", "Receptie", ContactSource.InternalExtensions, listOf("100")),
        entry("d1", "Oma", ContactSource.Device, listOf("0201234567")),
    )

    private fun sections(scope: ContactScope = ContactScope.CENTRALE, filter: ContactListFilter = ContactListFilter.All, query: String = "", favorites: Set<String> = emptySet()) =
        ContactsScreenBrowsing.sections(entries, scope, filter, query, favorites) { _, _ -> setOf("a", "b") }

    private fun ids(sections: List<ContactSection>) = sections.flatMap { it.entries }.map { it.id }

    @Test
    fun theSegmentsSeparateThePhoneSystemFromThePhone() {
        assertEquals(setOf("a", "b", "m", "p", "z", "i1"), ids(sections(ContactScope.CENTRALE)).toSet())
        assertEquals(listOf("d1"), ids(sections(ContactScope.PHONE)))
    }

    @Test
    fun theFiltersNarrowTheSegment() {
        assertEquals(listOf("i1"), ids(sections(filter = ContactListFilter.InternalOnly)))
        assertEquals(setOf("a", "b"), ids(sections(filter = ContactListFilter.List("x", "l"))).toSet())
        assertEquals(listOf("m"), ids(sections(filter = ContactListFilter.Favorites, favorites = setOf("m", "d1"))))
        assertEquals(listOf("d1"), ids(sections(ContactScope.PHONE, ContactListFilter.Favorites, favorites = setOf("m", "d1"))))
        assertTrue(sections(filter = ContactListFilter.Favorites).isEmpty())
    }

    @Test
    fun searchWorksOnNameAndNumber() {
        assertEquals(listOf("p"), ids(sections(query = "pieter")))
        assertEquals(setOf("a", "b", "m", "p", "z"), ids(sections(query = "0612")).toSet())
    }

    @Test
    fun theIndexJumpsToTheSectionOfTheLetter() {
        val all = sections()

        assertEquals(listOf('A', 'B', 'M', 'P', 'R', 'Z'), all.map { it.letter })
        assertEquals('M', AlphabetIndex.target('M', all))
        assertEquals('Z', AlphabetIndex.target('Z', all))
    }

    @Test
    fun aLetterWithoutContactsJumpsToTheNextSection() {
        val all = sections()

        assertEquals('M', AlphabetIndex.target('C', all))
        assertEquals('P', AlphabetIndex.target('N', all))
        assertEquals('Z', AlphabetIndex.target('S', all))
        // Past the last section: the last one. `#` has none here either.
        assertEquals('Z', AlphabetIndex.target('#', all))
        assertNull(AlphabetIndex.target('A', emptyList()))
    }

    @Test
    fun aFingerOnTheStripPicksTheLetterUnderIt() {
        assertEquals('A', AlphabetIndex.letter(0.0))
        assertEquals('#', AlphabetIndex.letter(1.0))
        assertEquals('A', AlphabetIndex.letter(-3.0))
        assertEquals(AlphabetIndex.letters[AlphabetIndex.letters.size / 2], AlphabetIndex.letter(0.5))
        assertEquals(27, AlphabetIndex.letters.size)
    }

    @Test
    fun favouritesAreKeptOnThePhone() {
        val store = InMemoryKeyValueStore()

        val first = FavoriteContacts(store)
        first.toggle("a")
        first.toggle("b")
        first.toggle("a")

        assertEquals(setOf("b"), first.ids.value)
        assertEquals("survives a restart", setOf("b"), FavoriteContacts(store).ids.value)
        assertTrue(FavoriteContacts(store).contains("b"))
    }

    @Test
    fun saveNeedsANumberOrANameWithAnEmailAddress() {
        var draft = ContactDraft()
        assertFalse("empty", ContactEditRules.canSave(draft))

        draft = draft.copy(firstName = "Anna")
        assertFalse("a name nobody can reach is refused by the server", ContactEditRules.canSave(draft))

        draft = draft.copy(email = "anna@example.nl")
        assertTrue(ContactEditRules.canSave(draft))

        draft = draft.copy(email = "", phones = listOf(draft.phones[0].copy(number = "0612345678")))
        assertTrue(ContactEditRules.canSave(draft))

        val numberOnly = ContactDraft(phones = listOf(ContactDraft.Phone(number = "0612345678")))
        assertTrue("a number alone is enough", ContactEditRules.canSave(numberOnly))
        assertEquals("named after the number", "0612345678", ContactEditRules.prepared(numberOnly, emptyMap()).displayName)
    }

    @Test
    fun theCountryCodeIsAddedToANationalNumber() {
        val belgium = PhoneCountry("BE", "32")

        assertEquals("+32471 23 45 67", belgium.apply("0471 23 45 67"))
        assertEquals("a number with its own code stays", "+31612345678", belgium.apply("+31612345678"))
        assertEquals("+32471234567", belgium.apply("0032471234567"))
        assertEquals("Dutch numbers stay as typed", "0612345678", PhoneCountry.NETHERLANDS.apply("0612345678"))
        assertEquals("", belgium.apply("  "))
    }

    @Test
    fun theNetherlandsComesFirstInTheCountryList() {
        assertEquals(PhoneCountry.NETHERLANDS, PhoneCountry.all.first())
        assertEquals(PhoneCountry.all.size, PhoneCountry.all.map { it.region }.toSet().size)
        assertEquals("BE", PhoneCountry.detect("+32471234567")?.region)
        assertEquals("NL", PhoneCountry.detect("0031612345678")?.region)
        assertEquals("LU", PhoneCountry.detect("+352621123456")?.region)
        assertNull(PhoneCountry.detect("0612345678"))
    }

    @Test
    fun preparedAppliesThePickedCountryPerNumber() {
        val draft = ContactDraft(
            firstName = "Jan",
            phones = listOf(ContactDraft.Phone(number = "0471234567"), ContactDraft.Phone(number = "0612345678")),
        )
        val countries = mapOf(draft.phones[0].id to PhoneCountry("BE", "32"))

        val prepared = ContactEditRules.prepared(draft, countries)

        assertEquals(listOf("+32471234567", "0612345678"), prepared.phones.map { it.number })
        assertEquals(draft.phones.map { it.id }, prepared.phones.map { it.id })
    }

    private fun call(id: String, number: String, ago: Long) = RecentCall(
        id = id, number = number, name = null, accountId = "acc", accountLabel = "Kantoor",
        direction = RecentCall.Direction.OUTGOING, outcome = RecentCall.Outcome.ANSWERED, startedAtMillis = 1_000_000 - ago, durationSeconds = 30,
    )

    @Test
    fun theDetailShowsTheCallsWithThatNumberNewestFirst() {
        val calls = listOf(call("old", "+31612345678", 500), call("other", "0698765432", 100), call("new", "0612345678", 50), call("anon", "", 10))

        assertEquals("0612... and +31612... are the same number", listOf("new", "old"), ContactRecentCalls.matching(calls, listOf("0612345678")).map { it.id })
        assertEquals(0, ContactRecentCalls.matching(calls, emptyList()).size)
        assertEquals(listOf("new"), ContactRecentCalls.matching(calls, listOf("0612345678"), limit = 1).map { it.id })
    }

    @Test
    fun failuresMapToAReasonNeverToTheServersWords() {
        assertEquals(ContactFailure.PHONE, ContactFailure.of(ApiException.Invalid("invalid_phone", "phones")))
        assertEquals(ContactFailure.EMAIL, ContactFailure.of(ApiException.Invalid("invalid_email", "email")))
        assertEquals(ContactFailure.GENERIC, ContactFailure.of(ApiException.Invalid("other", null)))
        assertEquals(ContactFailure.FORBIDDEN, ContactFailure.of(ApiException.Forbidden()))
        assertEquals(ContactFailure.LIMIT, ContactFailure.of(ApiException.Conflict("limit_reached")))
        assertEquals(ContactFailure.GENERIC, ContactFailure.of(ApiException.Conflict("busy")))
        assertEquals(ContactFailure.RATE, ContactFailure.of(ApiException.RateLimited(60)))
        assertEquals(ContactFailure.NETWORK, ContactFailure.of(ApiException.Transport("offline")))
        assertEquals(ContactFailure.NETWORK, ContactFailure.of(ApiException.Unavailable(true, null)))
        assertEquals(ContactFailure.GONE, ContactFailure.of(ApiException.NotFound()))
        assertEquals(ContactFailure.STALE, ContactFailure.of(ApiException.Stale(null)))
        assertEquals(ContactFailure.GENERIC, ContactFailure.of(IllegalStateException()))
    }

    @Test
    fun timelineIconsPerKindAndASafeFallback() {
        assertEquals(TimelineSymbol.ORDER, TimelineSymbol.of("order"))
        assertEquals(TimelineSymbol.TICKET, TimelineSymbol.of("TICKET"))
        assertEquals(TimelineSymbol.CALL, TimelineSymbol.of("voicecall"))
        assertEquals(TimelineSymbol.OTHER, TimelineSymbol.of("something_new"))
    }
}
