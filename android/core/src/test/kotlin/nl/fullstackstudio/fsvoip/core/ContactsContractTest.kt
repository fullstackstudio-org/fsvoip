// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The contacts fixtures (address book sync, detail, edit, lists, timeline) against the Kotlin models. */
class ContactsContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += name
        return Fixtures.text(name)
    }

    /** Runs every test of this class and returns the fixture names they decoded (see [ContractRegistry]). */
    fun coverage(): Set<String> {
        val tests = ContactsContractTest()
        tests.contactsPage()
        tests.contactsSince()
        tests.detailAndResponses()
        tests.requests()
        tests.lists()
        tests.timeline()
        tests.errors()

        return tests.covered
    }

    @Test
    fun contactsPage() {
        val page = json.decodeFromString(ContactsPage.serializer(), fixture("contacts-page.json"))

        assertEquals(2, page.contacts.size)
        assertTrue(page.deleted.isEmpty())
        assertNull(page.nextCursor)
        assertEquals("2026-10-09T08:15:30.123Z", page.serverTime)

        val smit = page.contacts[0]
        assertEquals("Bakkerij Smit", smit.name)
        assertEquals("Bakkerij Smit", smit.company)
        assertNull(smit.firstName)
        assertEquals(listOf(ContactPhone("+31701234567", ContactPhoneLabel.WORK, true)), smit.phones)
        assertEquals(listOf("klant"), smit.tags)
        assertEquals("2026-10-07T09:30:12.345Z", smit.updatedAt)
        assertNotNull(smit.updatedAtInstant)
    }

    @Test
    fun contactsSince() {
        val page = json.decodeFromString(ContactsPage.serializer(), fixture("contacts-since.json"))

        assertEquals(listOf("7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d"), page.contacts.map { it.id })
        assertEquals(listOf("8b9c0d1e-2f3a-4b4c-8d5e-6f7a8b9c0d1e"), page.deleted)
        assertNotNull(page.nextCursor)
        assertEquals(listOf(ContactPhoneLabel.MOBILE, ContactPhoneLabel.WORK), page.contacts[0].phones.map { it.label })
    }

    @Test
    fun detailAndResponses() {
        val detail = json.decodeFromString(ContactSingleResponse.serializer(), fixture("contact-detail.json")).contact

        assertEquals("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c", detail.id)
        assertEquals("Bakkerij Smit", detail.contact.name)
        assertEquals("Levert brood op maandag.", detail.notes)
        assertEquals(listOf("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), detail.listIds)

        val updated = json.decodeFromString(ContactUpdateResponse.serializer(), fixture("contact-update-response.json"))
        assertTrue(updated.changed)
        assertEquals(2, updated.contact.contact.phones.size)
        assertEquals("2026-10-09T08:20:00.000Z", updated.contact.contact.updatedAt)
        assertEquals(1, json.decodeFromString(ContactDeleteResponse.serializer(), fixture("contact-delete-response.json")).deleted)
    }

    @Test
    fun requests() {
        val create = ContactCreate(
            name = "Bakkerij Smit",
            company = Change.To("Bakkerij Smit"),
            email = Change.To("info@bakkerijsmit.nl"),
            phones = listOf(ContactPhone("+31701234567", ContactPhoneLabel.WORK, true)),
            tags = listOf("klant"),
            listIds = listOf("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"),
        )
        assertEquals(Fixtures.json("contact-create-request.json").also { fixture("contact-create-request.json") }, create.toJson())

        val update = ContactUpdate(
            expectedUpdatedAt = "2026-10-07T09:30:12.345Z",
            firstName = Change.Clear,
            notes = Change.To("Nieuw nummer van de eigenaar."),
            phones = listOf(ContactPhone("+31701234567", ContactPhoneLabel.WORK, true), ContactPhone("+31612345678", ContactPhoneLabel.MOBILE, false)),
        )
        assertEquals(Fixtures.json("contact-update-request.json").also { fixture("contact-update-request.json") }, update.toJson())
    }

    @Test
    fun anUpdateAlwaysCarriesExpectedUpdatedAtAndAKeptFieldStaysOut() {
        val body = ContactUpdate("2026-10-07T09:30:12.345Z", company = Change.Clear).toJson()

        assertEquals("2026-10-07T09:30:12.345Z", (body["expectedUpdatedAt"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(JsonNull, body["company"])
        assertFalse(body.containsKey("email"))
        assertFalse(body.containsKey("phones"))
        assertFalse(body.containsKey("tags"))
    }

    @Test
    fun lists() {
        val lists = json.decodeFromString(ContactListsResponse.serializer(), fixture("contact-lists.json")).lists

        assertEquals(listOf("Klanten", "Leveranciers"), lists.map { it.name })
        assertEquals(3, lists[0].version)
        assertEquals(0, lists[1].contactCount)
        assertEquals("\"3\"", ContactListETag.make(lists[0].version))

        val snapshot = json.decodeFromString(ContactListSnapshot.serializer(), fixture("contact-list-snapshot.json"))
        assertEquals(3, snapshot.list.version)
        assertEquals(2, snapshot.contacts.size)
        assertEquals(listOf(ContactPhoneLabel.MOBILE, ContactPhoneLabel.WORK), snapshot.contacts[1].phones.map { it.label })
        assertNull(snapshot.contacts[1].company)
        assertNull(snapshot.nextCursor)
    }

    @Test
    fun timeline() {
        val first = json.decodeFromString(TimelinePage.serializer(), fixture("contact-timeline.json"))
        assertEquals(listOf("e1", "e2"), first.items.map { it.id })
        assertEquals("order", first.items[0].kind)
        assertEquals("W-1042", first.items[0].ref)
        assertEquals("3 producten, € 84,50", first.items[0].summary)
        assertNotNull(first.items[0].occurredAtInstant)
        assertEquals("MjAyNi0xMC0wOFQwOToxNTowMC4wMDBafGUy", first.nextCursor)

        val last = json.decodeFromString(TimelinePage.serializer(), fixture("contact-timeline-last.json"))
        assertEquals(listOf("e3"), last.items.map { it.id })
        assertNull(last.items[0].summary)
        assertNull(last.items[0].ref)
        assertNull(last.nextCursor)
    }

    @Test
    fun errors() {
        assertEquals("limit_reached", (FsVoipApiClient.errorFor(response(409, fixture("error-conflict-limit.json"))) as ApiException.Conflict).code)
        assertTrue(FsVoipApiClient.errorFor(response(400, fixture("error-resync.json"))) is ApiException.Resync)

        val stale = FsVoipApiClient.errorFor(response(409, fixture("error-stale-contact.json")))
        assertTrue(stale is ApiException.Stale)
        assertNull((stale as ApiException.Stale).version)
    }

    @Test
    fun anUnknownPhoneLabelBecomesUnknownButCanNotBeSent() {
        val phone = json.decodeFromString(ContactPhone.serializer(), """{"number":"+31701234567","label":"pager"}""")

        assertEquals(ContactPhoneLabel.UNKNOWN, phone.label)
        assertFalse(phone.isPrimary)

        try {
            json.encodeToString(ContactPhone.serializer(), phone)
            fail("an unknown label cannot be sent")
        } catch (_: IllegalArgumentException) {
        } catch (_: SerializationException) {
        }
    }

    @Test
    fun responsesIgnoreUnknownFieldsAndMissingOptionalOnes() {
        val future = Fixtures.text("contact-detail.json").replace("\"notes\"", "\"future\": 1, \"notes\"")
        assertEquals("Bakkerij Smit", json.decodeFromString(ContactSingleResponse.serializer(), future).contact.contact.name)

        val bare = json.decodeFromString(ContactSingleResponse.serializer(), """{"contact":{"id":"a","updatedAt":"t"}}""").contact
        assertEquals("", bare.contact.name)
        assertEquals(emptyList<ContactPhone>(), bare.contact.phones)
        assertNull(bare.notes)
        assertEquals(emptyList<String>(), bare.listIds)
    }

    @Test
    fun meCarriesTheContactTotal() {
        val me = json.decodeFromString(MeResponse.serializer(), Fixtures.text("me-response-admin.json"))
        assertEquals(148, me.contacts.total)
        assertEquals(2, me.contacts.listsAvailable)
        assertNull(json.decodeFromString(MeResponse.serializer(), Fixtures.text("me-response-no-sip.json")).contacts.total)
    }
}
