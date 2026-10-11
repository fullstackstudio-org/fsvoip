// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.fullstackstudio.fsvoip.core.ContactPhoneLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDraftTest {
    private fun text(body: JsonObject, key: String) = body[key]?.jsonPrimitive?.content

    @Test
    fun aNewContactNeedsANameAndAWayToReachIt() {
        var draft = ContactDraft()
        assertFalse(draft.canSave)

        draft = draft.copy(firstName = "Pieter")
        assertFalse("a name alone is not reachable", draft.canSave)

        draft = draft.copy(phones = listOf(draft.phones[0].copy(number = "06 12 34 56 78")))
        assertTrue(draft.canSave)

        draft = draft.copy(phones = listOf(draft.phones[0].copy(number = "")), email = "p@example.nl")
        assertTrue(draft.canSave)

        draft = draft.copy(firstName = "")
        assertFalse(draft.canSave)
        draft = draft.copy(company = "Smit")
        assertTrue("a company is a name", draft.canSave)
    }

    @Test
    fun createSendsNumbersInInternationalFormAndLeavesEmptyFieldsOut() {
        val draft = ContactDraft(
            firstName = " Pieter ",
            lastName = "de Groot",
            phones = listOf(
                ContactDraft.Phone(number = "06 12 34 56 78", label = ContactPhoneLabel.MOBILE),
                ContactDraft.Phone(number = "", label = ContactPhoneLabel.WORK),
                ContactDraft.Phone(number = "070-1234567", label = ContactPhoneLabel.WORK),
            ),
            listIds = setOf("l2", "l1"),
        )

        val json = draft.createRequest().toJson()

        assertEquals("Pieter de Groot", text(json, "name"))
        assertEquals("Pieter", text(json, "firstName"))
        assertNull("an empty field is left out on create", json["company"])
        assertNull(json["notes"])
        assertEquals(listOf("l1", "l2"), json["listIds"]!!.jsonArray.map { it.jsonPrimitive.content })

        val phones: JsonArray = json["phones"]!!.jsonArray
        assertEquals(listOf("+31612345678", "+31701234567"), phones.map { it.jsonObject["number"]!!.jsonPrimitive.content })
        assertEquals(listOf(true, false), phones.map { it.jsonObject["isPrimary"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun updateAlwaysCarriesExpectedUpdatedAtAndClearsEmptiedFields() {
        val detail = Make.detail(Make.contact("c1", "Pieter de Groot", listOf("+31612345678"), company = "Smit", email = "p@example.nl"), notes = "n", listIds = listOf("l1"))
        val draft = ContactDraft.from(detail).copy(company = "", notes = "")

        val json = draft.updateRequest("2026-10-09T10:00:00.000Z").toJson()

        assertEquals("2026-10-09T10:00:00.000Z", text(json, "expectedUpdatedAt"))
        assertEquals("an emptied field is cleared with null", JsonNull, json["company"])
        assertEquals(JsonNull, json["notes"])
        assertEquals("p@example.nl", text(json, "email"))
        assertEquals(listOf("l1"), json["listIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull("tags are not edited on the phone: left unchanged", json["tags"])
        assertEquals(JsonPrimitive("Pieter de Groot"), json["name"])
    }

    @Test
    fun aContactThatOnlyHasADisplayNameKeepsIt() {
        val draft = ContactDraft.from(Make.detail(Make.contact("c1", "Pieter de Groot", listOf("+31612345678"))))

        assertEquals("Pieter de Groot", draft.displayName)
        assertEquals(emptySet<String>(), draft.listIds)
    }

    @Test
    fun aCompanyContactStaysACompany() {
        val draft = ContactDraft.from(Make.detail(Make.contact("c1", "Bakkerij Smit", listOf("+31701234567"), company = "Bakkerij Smit")))

        assertEquals("Bakkerij Smit", draft.company)
        assertEquals("", draft.firstName)
        assertEquals("Bakkerij Smit", draft.displayName)
    }

    @Test
    fun aContactWithoutNumbersGetsOneEmptyRow() {
        assertEquals(1, ContactDraft.from(Make.detail(Make.contact("c1", "Anna", email = "a@b.nl"))).phones.size)
    }

    @Test
    fun anEntryIsSplitIntoFirstAndLastName() {
        val entry = ContactEntry("customer:c", "Pieter de Groot", null, null, listOf(ContactPhoneEntry("+31612345678")), ContactSource.Customer)
        val draft = ContactDraft.from(entry)

        assertEquals("Pieter", draft.firstName)
        assertEquals("de Groot", draft.lastName)
    }
}
