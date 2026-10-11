// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.Contact
import nl.fullstackstudio.fsvoip.core.ContactCreate
import nl.fullstackstudio.fsvoip.core.ContactListsResponse
import nl.fullstackstudio.fsvoip.core.ContactListSnapshotOutcome
import nl.fullstackstudio.fsvoip.core.ContactPhone
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import nl.fullstackstudio.fsvoip.core.ContactUpdate
import nl.fullstackstudio.fsvoip.core.ContactsPage
import nl.fullstackstudio.fsvoip.core.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDraftTest {
    @Test
    fun aContactNeedsANameAndAWayToReachIt() {
        assertFalse(ContactDraft().canSave)
        assertFalse(ContactDraft(firstName = "Piet").canSave)
        assertTrue(ContactDraft(firstName = "Piet", email = "piet@example.nl").canSave)
        assertTrue(ContactDraft(company = "Smit", phones = listOf(ContactDraftPhone(0, "0612345678"))).canSave)
        // A row the user left empty does not count.
        assertFalse(ContactDraft(firstName = "Piet", phones = listOf(ContactDraftPhone(0, "  "))).canSave)
    }

    @Test
    fun theDisplayNameIsThePersonThenTheCompany() {
        assertEquals("Piet de Vries", ContactDraft(firstName = " Piet ", lastName = "de Vries", company = "Smit").displayName)
        assertEquals("Smit", ContactDraft(company = " Smit ").displayName)
    }

    @Test
    fun createLeavesEmptyFieldsOutAndWritesNumbersAsE164() {
        val body = ContactDraft(firstName = "Piet", phones = listOf(ContactDraftPhone(0, "06 12 34 56 78", ContactPhoneLabel.WORK), ContactDraftPhone(1, "112"))).createRequest().toJson()

        assertEquals(JsonPrimitive("Piet"), body["name"])
        assertFalse(body.containsKey("company"))
        assertFalse(body.containsKey("email"))
        assertFalse(body.containsKey("listIds"))

        val phones = body["phones"]!!.jsonArray

        assertEquals(JsonPrimitive("+31612345678"), phones[0].jsonObject["number"])
        assertEquals(JsonPrimitive("work"), phones[0].jsonObject["label"])
        assertEquals(JsonPrimitive(true), phones[0].jsonObject["isPrimary"])
        // A short number stays as typed.
        assertEquals(JsonPrimitive("112"), phones[1].jsonObject["number"])
        assertEquals(JsonPrimitive(false), phones[1].jsonObject["isPrimary"])
    }

    @Test
    fun updateSendsTheExpectedTimestampUnchangedAndWipesEmptiedFields() {
        val body = ContactDraft(firstName = "Piet", email = "", listIds = setOf("b", "a")).updateRequest("2026-10-07T09:30:12.345Z").toJson()

        assertEquals(JsonPrimitive("2026-10-07T09:30:12.345Z"), body["expectedUpdatedAt"])
        // `null` wipes a text field; the list and phones replace the whole set.
        assertEquals(JsonNull, body["email"])
        assertEquals(JsonNull, body["company"])
        assertEquals(listOf("a", "b"), body["listIds"]!!.jsonArray.map { (it as JsonPrimitive).content })
    }

    @Test
    fun aContactWithOnlyADisplayNameKeepsItOnTheFirstLine() {
        val draft = ContactDraft.from(Contact(id = "1", name = "Bakkerij Smit", updatedAt = "t"))

        assertEquals("Bakkerij Smit", draft.firstName)

        val company = ContactDraft.from(Contact(id = "2", name = "Smit BV", company = "Smit BV", updatedAt = "t"))

        assertEquals("", company.firstName)
        assertEquals("Smit BV", company.company)
        // An empty draft always has one row to type in.
        assertEquals(1, company.phones.size)
        assertEquals(1, company.removingPhone(0).phones.size)
    }
}

class ContactEditingTest {
    private val store = InMemoryContactsStore()
    private val api = EditingApi()

    private fun hub() = ContactsHub(store, api = { api }, now = { 1_000_000L })

    @Test
    fun aCreatedContactIsInTheStoredCopyAndFindableByNumber() = runTest {
        val hub = hub()
        hub.setAccounts(listOf("acc"))

        val result = hub.create(ContactDraft(firstName = "Piet", phones = listOf(ContactDraftPhone(0, "0612345678"))), "acc")

        assertTrue(result is ContactWrite.Done)
        assertEquals("Piet", hub.name("+31612345678"))
        assertEquals(1, store.load("acc")!!.contacts.size)
        assertTrue(api.last is ContactCreate)
    }

    @Test
    fun anUpdateReplacesTheStoredContact() = runTest {
        val hub = hub()
        api.pages += ContactsPage(listOf(contact("1", "Oud", "0612345678")), emptyList(), null, "T1")
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")

        val result = hub.update(ContactDraft(firstName = "Nieuw", phones = listOf(ContactDraftPhone(0, "0612345678"))), "1", "2026-10-07T09:30:12.345Z", "acc")

        assertTrue(result is ContactWrite.Done)
        assertEquals("Nieuw", hub.name("0612345678"))
        assertEquals("2026-10-07T09:30:12.345Z", (api.last as ContactUpdate).expectedUpdatedAt)
    }

    @Test
    fun aStaleUpdateIsReportedAndChangesNothing() = runTest {
        val hub = hub()
        api.pages += ContactsPage(listOf(contact("1", "Oud", "0612345678")), emptyList(), null, "T1")
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")
        api.fail = ApiException.Conflict("stale", null, null, null, null)

        val result = hub.update(ContactDraft(firstName = "Nieuw"), "1", "old", "acc")

        assertEquals(ContactWrite.Failed(ContactFailure.STALE), result)
        assertEquals("Oud", hub.name("0612345678"))
    }

    @Test
    fun deletingRemovesTheContactAndItsNumber() = runTest {
        val hub = hub()
        api.pages += ContactsPage(listOf(contact("1", "Piet", "0612345678")), emptyList(), null, "T1")
        hub.setAccounts(listOf("acc"))
        hub.sync("acc")

        assertNull(hub.delete("1", "acc"))
        assertNull(hub.name("0612345678"))
        assertEquals(0, store.load("acc")!!.contacts.size)
    }

    @Test
    fun failuresAreMappedToReasonsNeverToServerWords() {
        assertEquals(ContactFailure.PHONE, ContactFailure.of(ApiException.InvalidRequest("x", "invalid_phone", "phones")))
        assertEquals(ContactFailure.EMAIL, ContactFailure.of(ApiException.InvalidRequest(null, "invalid_email", "email")))
        assertEquals(ContactFailure.OTHER, ContactFailure.of(ApiException.InvalidRequest(null, "whatever", null)))
        assertEquals(ContactFailure.FORBIDDEN, ContactFailure.of(ApiException.Forbidden("admin", null)))
        assertEquals(ContactFailure.LIMIT, ContactFailure.of(ApiException.Conflict("conflict", "limit_reached", null, null, null)))
        assertEquals(ContactFailure.GONE, ContactFailure.of(ApiException.NotFound()))
        assertEquals(ContactFailure.REVOKED, ContactFailure.of(ApiException.Unauthorized()))
    }
}

class FavoriteContactsTest {
    @Test
    fun favouritesSurviveARestartAndStayOnThePhone() {
        val store = InMemoryKeyValueStore()
        val favourites = FavoriteContacts(store)

        favourites.toggle("acc/1")
        favourites.toggle("acc/2")
        favourites.toggle("acc/1")

        assertEquals(setOf("acc/2"), favourites.ids.value)
        assertEquals(setOf("acc/2"), FavoriteContacts(store).ids.value)

        favourites.toggle("acc/2")

        assertEquals(emptySet<String>(), FavoriteContacts(store).ids.value)
    }
}

/** The sync fake of the other tests, with the changes answered like the server does. */
private class EditingApi : ContactsApi {
    var pages = ArrayDeque<ContactsPage>()
    var last: Any? = null
    var fail: Exception? = null

    override suspend fun contacts(since: String?, cursor: String?, limit: Int?): ContactsPage = pages.removeFirstOrNull() ?: ContactsPage(emptyList(), emptyList(), null, "T")

    override suspend fun contactLists() = ContactListsResponse()

    override suspend fun contactListSnapshot(listId: String, cursor: String?, etag: String?): ContactListSnapshotOutcome = error("no lists")

    override suspend fun createContact(request: ContactCreate): Contact {
        fail?.let { throw it }
        last = request

        return Contact(
            id = "new",
            name = request.name.orEmpty(),
            phones = request.phones.orEmpty().map { ContactPhone(it.number, it.label, it.isPrimary) },
            updatedAt = "2026-10-12T10:00:00.000Z",
        )
    }

    override suspend fun updateContact(id: String, request: ContactUpdate): Contact {
        fail?.let { throw it }
        last = request

        return Contact(id = id, name = request.name.orEmpty(), phones = request.phones.orEmpty(), updatedAt = "2026-10-12T10:00:01.000Z")
    }

    override suspend fun deleteContact(id: String) {
        fail?.let { throw it }
    }
}
